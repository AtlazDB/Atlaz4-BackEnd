package geoRural.service;

import geoRural.dto.ColetaResponse;
import geoRural.entity.ArquivoBruto;
import geoRural.entity.Fonte;
import geoRural.exception.ColetaFalhouException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.ArquivoBrutoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O "Executar agora" de uma fonte por link: baixa o link para a zona bruta e registra o arquivo
 * como RECEBIDO. Daí em diante é igual a um upload: o processamento (Java ou Airflow) pega o
 * arquivo pelo status.
 *
 * O download vai para um arquivo temporário, com o SHA-256 calculado no caminho: a memória não
 * depende do tamanho do arquivo, e dá para saber se o conteúdo mudou antes de subir qualquer
 * coisa. Conteúdo igual ao da última coleta da fonte não é gravado ("sem mudança").
 *
 * Fica para a coleta do Airflow (Sprint 3): paginação de serviços (ArcGIS, WFS), agendamento e
 * downloads longos sem segurar a requisição da tela.
 */
@Service
public class ColetaLinkServiceImpl implements ColetaLinkService {

    private static final Logger log = LoggerFactory.getLogger(ColetaLinkServiceImpl.class);

    private static final Pattern NOME_NO_CABECALHO = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final FonteService fonteService;
    private final ArquivoBrutoRepository repository;
    private final ObjectStorageService objectStorageService;
    private final ClienteHttpExterno cliente;
    private final long limiteBytes;

    @Value("${oci.zona-bruta}")
    private String zonaBruta;

    public ColetaLinkServiceImpl(FonteService fonteService,
                                 ArquivoBrutoRepository repository,
                                 ObjectStorageService objectStorageService,
                                 ClienteHttpExterno cliente,
                                 @Value("${app.coleta.limite-bytes:2147483648}") long limiteBytes) {
        this.fonteService = fonteService;
        this.repository = repository;
        this.objectStorageService = objectStorageService;
        this.cliente = cliente;
        this.limiteBytes = limiteBytes;
    }

    @Override
    public ColetaResponse executar(Long fonteId) {
        Fonte fonte = fonteService.buscarPorId(fonteId);
        if (fonte.getUrlOrigem() == null || fonte.getUrlOrigem().isBlank()) {
            throw new RequisicaoInvalidaException(
                    "Esta fonte não tem link de origem: cadastre a URL para poder coletar.", List.of("urlOrigem"));
        }

        Path temporario = null;
        try {
            temporario = Files.createTempFile("coleta-", ".tmp");
            Download download = baixar(fonte, temporario);

            Optional<ArquivoBruto> ultimo = repository.findByFonteIdOrderByRecebidoEmDesc(fonteId).stream().findFirst();
            if (ultimo.isPresent() && download.hash().equals(ultimo.get().getHashSha256())) {
                log.info("Fonte {}: coleta sem mudança (igual ao arquivo {})", fonteId, ultimo.get().getId());
                return ColetaResponse.semMudanca(ultimo.get());
            }

            String chave = ArquivoBrutoServiceImpl.chaveNaZonaBruta(zonaBruta, fonte.getSigla(), download.nome());
            try (InputStream in = Files.newInputStream(temporario)) {
                objectStorageService.enviar(chave, in, download.tamanho(), download.contentType());
            }

            ArquivoBruto bruto = new ArquivoBruto();
            bruto.setFonte(fonte);
            bruto.setChaveObjeto(chave);
            bruto.setNomeOriginal(download.nome());
            bruto.setTamanhoBytes(download.tamanho());
            bruto.setContentType(download.contentType());
            bruto.setHashSha256(download.hash());
            bruto.setStatus(ArquivoBruto.STATUS_RECEBIDO);
            ArquivoBruto salvo = repository.save(bruto);
            log.info("Fonte {}: coletado {} ({} bytes) em {}", fonteId, download.nome(), download.tamanho(), chave);
            return ColetaResponse.recebido(salvo);
        } catch (IOException e) {
            throw new ColetaFalhouException("Falha ao baixar o link da fonte: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ColetaFalhouException("A coleta foi interrompida.", e);
        } finally {
            if (temporario != null) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException e) {
                    log.warn("Não consegui apagar o temporário {}", temporario, e);
                }
            }
        }
    }

    private record Download(String nome, String contentType, long tamanho, String hash) {}

    private Download baixar(Fonte fonte, Path destino) throws IOException, InterruptedException {
        HttpResponse<InputStream> resposta = cliente.abrir(new ClienteHttpExterno.Pedido(
                fonte.getUrlOrigem(), fonte.getMetodoHttp(), fonte.getCabecalhos(), fonte.getCorpoRequisicao()));

        MessageDigest sha256 = sha256();
        long tamanho;
        try (InputStream in = new DigestInputStream(new Limitado(resposta.body(), limiteBytes), sha256)) {
            tamanho = Files.copy(in, destino, StandardCopyOption.REPLACE_EXISTING);
        }
        if (tamanho == 0) {
            throw new ColetaFalhouException("O servidor de origem respondeu sem conteúdo.");
        }

        String contentType = resposta.headers().firstValue("Content-Type")
                .map(tipo -> tipo.split(";")[0].trim().toLowerCase(Locale.ROOT))
                .orElse("application/octet-stream");
        String nome = nomeDoArquivo(resposta.uri(), resposta.headers().firstValue("Content-Disposition").orElse(null),
                contentType, fonte);
        return new Download(nome, contentType, tamanho, HexFormat.of().formatHex(sha256.digest()));
    }

    /**
     * O nome que o arquivo ganha na zona bruta, com uma extensão que ela aceita: o que o servidor
     * sugere (Content-Disposition), senão o fim do link, senão o tipo do conteúdo ou o formato da
     * fonte, com a sigla e a hora da coleta.
     */
    static String nomeDoArquivo(URI uri, String contentDisposition, String contentType, Fonte fonte) {
        if (contentDisposition != null) {
            Matcher m = NOME_NO_CABECALHO.matcher(contentDisposition);
            if (m.find() && extensaoAceita(m.group(1))) {
                return limpar(m.group(1));
            }
        }
        String caminho = uri.getPath();
        if (caminho != null) {
            String ultimo = caminho.substring(caminho.lastIndexOf('/') + 1);
            if (extensaoAceita(ultimo)) {
                return limpar(ultimo);
            }
        }
        return fonte.getSigla().toLowerCase(Locale.ROOT) + "_" + LocalDateTime.now(ZoneOffset.UTC).format(CARIMBO)
                + extensaoPeloTipo(contentType, fonte.getFormato());
    }

    private static String extensaoPeloTipo(String contentType, String formatoDaFonte) {
        if (contentType.contains("zip")) return ".zip";
        if (contentType.contains("csv")) return ".csv";
        if (contentType.contains("geo+json")) return ".geojson";
        if (contentType.contains("spreadsheetml")) return ".xlsx";
        return switch (formatoDaFonte == null ? "" : formatoDaFonte.toUpperCase(Locale.ROOT)) {
            case "CSV" -> ".csv";
            case "GEOJSON" -> ".geojson";
            case "SHP" -> ".zip";
            case "XLSX" -> ".xlsx";
            case "JSON" -> ".json";
            default -> contentType.contains("json") ? ".json" : ".txt";
        };
    }

    private static boolean extensaoAceita(String nome) {
        int i = nome.lastIndexOf('.');
        return i > 0 && ArquivoBrutoServiceImpl.extensoesAceitas().contains(nome.substring(i).toLowerCase(Locale.ROOT));
    }

    /** O nome vai para a chave do objeto no bucket: só letras, números, ponto, hífen e sublinhado. */
    private static String limpar(String nome) {
        String limpo = nome.replaceAll("[^A-Za-z0-9._-]", "_");
        return limpo.length() <= 150 ? limpo : limpo.substring(limpo.length() - 150);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Para de ler, com erro, quando o servidor manda mais que o limite: protege o disco. */
    private static final class Limitado extends FilterInputStream {
        private final long limite;
        private long lidos;

        Limitado(InputStream in, long limite) {
            super(in);
            this.limite = limite;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) contar(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int inicio, int tamanho) throws IOException {
            int n = super.read(buffer, inicio, tamanho);
            if (n > 0) contar(n);
            return n;
        }

        private void contar(int n) {
            lidos += n;
            if (lidos > limite) {
                throw new ColetaFalhouException("O arquivo passou do limite de "
                        + (limite / (1024 * 1024)) + " MB da coleta por link.");
            }
        }
    }
}
