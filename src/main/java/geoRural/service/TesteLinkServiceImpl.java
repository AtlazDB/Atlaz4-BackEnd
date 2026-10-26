package geoRural.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.dto.TesteLinkRequest;
import geoRural.dto.TesteLinkResponse;
import geoRural.exception.ColetaFalhouException;
import geoRural.exception.RequisicaoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Testar link": pergunta ao link sem baixar tudo. Entende três tipos:
 *
 *   SERVICO_ARCGIS  .../FeatureServer/0/query (ou MapServer): amostra com resultRecordCount=5,
 *                   total com returnCountOnly, limite por pedido nos metadados da camada;
 *   SERVICO_WFS     GeoServer e afins (service=WFS): amostra com count=5 (WFS 2.0) ou
 *                   maxFeatures=5 (1.x), total com resultType=hits;
 *   ARQUIVO         qualquer outro link: lê só o começo (64 KB).
 *
 * As colunas saem da primeira feição (GeoJSON), dos campos do ArcGIS ou do cabeçalho do CSV.
 * Uma falha ao descobrir o total não derruba o teste: o total só fica em branco.
 */
@Service
public class TesteLinkServiceImpl implements TesteLinkService {

    private static final Logger log = LoggerFactory.getLogger(TesteLinkServiceImpl.class);

    private static final int AMOSTRA = 5;
    private static final int LIMITE_AMOSTRA_SERVICO = 5 * 1024 * 1024;  // 5 feições, mesmo de polígonos grandes
    private static final int LIMITE_AMOSTRA_ARQUIVO = 64 * 1024;
    private static final Pattern TOTAL_WFS = Pattern.compile("number(?:Matched|OfFeatures)=\"(\\d+)\"");

    private final ClienteHttpExterno cliente;
    private final ObjectMapper objectMapper;

    public TesteLinkServiceImpl(ClienteHttpExterno cliente, ObjectMapper objectMapper) {
        this.cliente = cliente;
        this.objectMapper = objectMapper;
    }

    enum Tipo { SERVICO_ARCGIS, SERVICO_WFS, ARQUIVO }

    @Override
    public TesteLinkResponse testar(TesteLinkRequest request) {
        String url = request.urlOrigem() == null ? "" : request.urlOrigem().trim();
        if (url.isEmpty()) {
            throw new RequisicaoInvalidaException("Informe o link para testar.", List.of("urlOrigem"));
        }
        Tipo tipo = tipo(url);
        List<String> avisos = new ArrayList<>();

        try {
            // 1. A amostra: é ela que diz se o link responde e quais são as colunas.
            String urlAmostra = switch (tipo) {
                case SERVICO_ARCGIS -> comParametros(url, Map.of("resultRecordCount", String.valueOf(AMOSTRA),
                        "resultOffset", "0"), Set.of("returnCountOnly"));
                case SERVICO_WFS -> comParametros(url, wfs2(url)
                        ? Map.of("count", String.valueOf(AMOSTRA)) : Map.of("maxFeatures", String.valueOf(AMOSTRA)),
                        Set.of("resultType"));
                case ARQUIVO -> url;
            };
            Amostra amostra = ler(pedido(request, urlAmostra),
                    tipo == Tipo.ARQUIVO ? LIMITE_AMOSTRA_ARQUIVO : LIMITE_AMOSTRA_SERVICO);
            List<String> colunas = colunas(amostra, avisos);

            // 2. O total e o limite por pedido: só nos serviços, e sem derrubar o teste se falhar.
            Long total = null;
            Integer limite = null;
            if (tipo == Tipo.SERVICO_ARCGIS) {
                total = totalArcgis(request, url);
                limite = limiteArcgis(request, url);
                if (total != null && limite != null && total > limite) {
                    avisos.add("O serviço tem " + numero(total) + " registros, mas entrega no máximo " + numero(limite)
                            + " por pedido. A coleta de hoje faz um pedido só: viriam os primeiros " + numero(limite)
                            + ". Para trazer tudo, a coleta precisa paginar (resultOffset), ou o link precisa de um"
                            + " filtro (where) que caiba num pedido.");
                }
            } else if (tipo == Tipo.SERVICO_WFS) {
                total = totalWfs(request, url);
            }

            return new TesteLinkResponse(tipo.name(), amostra.status(), amostra.contentType(), total, limite,
                    colunas, avisos, resumo(amostra.status(), total, colunas));
        } catch (IOException e) {
            throw new ColetaFalhouException("Falha ao ler a resposta do link: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ColetaFalhouException("O teste foi interrompido.", e);
        }
    }

    static Tipo tipo(String url) {
        String minusculo = url.toLowerCase(Locale.ROOT);
        String caminho = minusculo.split("\\?", 2)[0];
        if ((caminho.contains("/featureserver/") || caminho.contains("/mapserver/")) && caminho.endsWith("/query")) {
            return Tipo.SERVICO_ARCGIS;
        }
        if (parametro(url, "service") != null && "wfs".equalsIgnoreCase(parametro(url, "service"))) {
            return Tipo.SERVICO_WFS;
        }
        return Tipo.ARQUIVO;
    }

    private record Amostra(int status, String contentType, byte[] bytes, boolean cortada) {}

    private Amostra ler(ClienteHttpExterno.Pedido pedido, int limite) throws IOException, InterruptedException {
        HttpResponse<InputStream> resposta = cliente.abrir(pedido);
        String contentType = resposta.headers().firstValue("Content-Type")
                .map(t -> t.split(";")[0].trim().toLowerCase(Locale.ROOT)).orElse("");
        try (InputStream in = resposta.body()) {
            byte[] bytes = in.readNBytes(limite);
            boolean cortada = bytes.length == limite && in.read() != -1;
            return new Amostra(resposta.statusCode(), contentType, bytes, cortada);
        }
    }

    /** Colunas da primeira feição (GeoJSON), dos campos do ArcGIS, ou do cabeçalho do CSV. */
    private List<String> colunas(Amostra amostra, List<String> avisos) {
        byte[] b = amostra.bytes();
        if (b.length >= 2 && b[0] == 'P' && b[1] == 'K') {
            avisos.add("O link entrega um arquivo compactado (zip): as colunas aparecem depois da coleta,"
                    + " quando o arquivo é aberto no processamento.");
            return List.of();
        }
        String texto = new String(b, StandardCharsets.UTF_8).stripLeading();
        if (texto.startsWith("{")) {
            if (amostra.cortada()) {
                avisos.add("A amostra veio grande demais para ler as colunas.");
                return List.of();
            }
            try {
                JsonNode raiz = objectMapper.readTree(texto);
                JsonNode feicao = raiz.path("features").path(0);
                JsonNode atributos = feicao.has("properties") ? feicao.path("properties") : feicao.path("attributes");
                List<String> nomes = new ArrayList<>();
                atributos.fieldNames().forEachRemaining(nomes::add);
                if (nomes.isEmpty()) {
                    raiz.path("fields").forEach(campo -> nomes.add(campo.path("name").asText()));
                }
                if (nomes.isEmpty()) {
                    avisos.add("O link respondeu, mas sem nenhum registro na amostra: confira o filtro do link.");
                }
                return nomes;
            } catch (IOException e) {
                avisos.add("A resposta parece JSON, mas não deu para ler as colunas.");
                return List.of();
            }
        }
        if (texto.startsWith("<")) {
            avisos.add("O link respondeu em XML/HTML. Para um serviço WFS, peça JSON (outputFormat=application/json)"
                    + " ou shapefile (outputFormat=SHAPE-ZIP).");
            return List.of();
        }
        // CSV: a primeira linha, com o separador mais frequente entre ; , e tabulação.
        String primeira = texto.lines().findFirst().orElse("").replace("﻿", "");
        char separador = separador(primeira);
        List<String> nomes = new ArrayList<>();
        for (String parte : primeira.split(Pattern.quote(String.valueOf(separador)), -1)) {
            String nome = parte.trim().replaceAll("^\"|\"$", "");
            if (!nome.isEmpty()) {
                nomes.add(nome);
            }
        }
        return nomes.size() > 1 ? nomes : List.of();
    }

    private Long totalArcgis(TesteLinkRequest request, String url) {
        String urlTotal = comParametros(url, Map.of("returnCountOnly", "true", "f", "json"),
                Set.of("resultRecordCount", "resultOffset"));
        return numeroDoJson(request, urlTotal, "count");
    }

    /** maxRecordCount nos metadados da camada: a URL do query sem o "/query". */
    private Integer limiteArcgis(TesteLinkRequest request, String url) {
        String camada = url.split("\\?", 2)[0].replaceFirst("(?i)/query$", "") + "?f=json";
        Long limite = numeroDoJson(request, camada, "maxRecordCount");
        return limite == null ? null : limite.intValue();
    }

    private Long totalWfs(TesteLinkRequest request, String url) {
        String urlTotal = comParametros(url, Map.of("resultType", "hits"), Set.of("count", "maxFeatures", "outputFormat"));
        try {
            Amostra resposta = ler(pedido(request, urlTotal), 64 * 1024);
            Matcher m = TOTAL_WFS.matcher(new String(resposta.bytes(), StandardCharsets.UTF_8));
            return m.find() ? Long.valueOf(m.group(1)) : null;
        } catch (RuntimeException | IOException e) {
            log.info("Teste de link: o total do WFS não veio ({})", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Long numeroDoJson(TesteLinkRequest request, String url, String campo) {
        try {
            Amostra resposta = ler(pedido(request, url), 1024 * 1024);
            JsonNode valor = objectMapper.readTree(resposta.bytes()).path(campo);
            return valor.canConvertToLong() ? valor.asLong() : null;
        } catch (RuntimeException | IOException e) {
            log.info("Teste de link: {} não veio de {} ({})", campo, url, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static ClienteHttpExterno.Pedido pedido(TesteLinkRequest request, String url) {
        return new ClienteHttpExterno.Pedido(url, request.metodoHttp(), request.cabecalhos(), request.corpoRequisicao());
    }

    /**
     * A URL com parâmetros trocados ou acrescentados e outros removidos, sem mexer no resto (o
     * where do ArcGIS e o CQL_FILTER do WFS continuam como vieram). Nomes sem diferenciar maiúscula.
     */
    static String comParametros(String url, Map<String, String> trocar, Set<String> remover) {
        String[] partes = url.split("\\?", 2);
        Map<String, String> pares = new LinkedHashMap<>();
        if (partes.length == 2 && !partes[1].isEmpty()) {
            for (String par : partes[1].split("&")) {
                if (par.isEmpty()) continue;
                String[] kv = par.split("=", 2);
                pares.put(kv[0], kv.length == 2 ? kv[1] : "");
            }
        }
        pares.keySet().removeIf(chave -> remover.stream().anyMatch(chave::equalsIgnoreCase)
                || trocar.keySet().stream().anyMatch(chave::equalsIgnoreCase));
        pares.putAll(trocar);
        StringBuilder sb = new StringBuilder(partes[0]).append('?');
        pares.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
        return sb.substring(0, sb.length() - 1);
    }

    private static String parametro(String url, String nome) {
        String[] partes = url.split("\\?", 2);
        if (partes.length < 2) return null;
        for (String par : partes[1].split("&")) {
            String[] kv = par.split("=", 2);
            if (kv[0].equalsIgnoreCase(nome)) return kv.length == 2 ? kv[1] : "";
        }
        return null;
    }

    private static boolean wfs2(String url) {
        String versao = parametro(url, "version");
        return versao == null || versao.startsWith("2");
    }

    private static char separador(String linha) {
        char melhor = ',';
        long maior = -1;
        for (char c : new char[] {';', ',', '\t'}) {
            long n = linha.chars().filter(x -> x == c).count();
            if (n > maior) { maior = n; melhor = c; }
        }
        return melhor;
    }

    private static String resumo(int status, Long total, List<String> colunas) {
        StringBuilder sb = new StringBuilder("O link respondeu (HTTP ").append(status).append(")");
        if (total != null) sb.append(" · ").append(numero(total)).append(" registros");
        if (!colunas.isEmpty()) sb.append(" · ").append(colunas.size()).append(" colunas");
        return sb.append('.').toString();
    }

    private static String numero(long n) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR")).format(n);
    }
}
