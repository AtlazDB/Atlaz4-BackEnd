package geoRural.service;

import geoRural.dto.FonteRequest;
import geoRural.entity.Fonte;
import geoRural.exception.FonteNaoEncontradaException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.exception.SiglaDuplicadaException;
import geoRural.repository.FonteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

@Service
public class FonteServiceImpl implements FonteService {

    private static final List<String> FORMATOS = List.of("CSV", "GeoJSON", "SHP", "XLSX", "JSON");
    private static final int SIGLA_MAX = 12;

    private final FonteRepository repository;

    public FonteServiceImpl(FonteRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Fonte> listar(String busca) {
        return vazio(busca)
                ? repository.findAllByOrderByCriadaEmDesc()
                : repository.buscar(busca.trim());
    }

    @Override
    @Transactional
    public Fonte cadastrar(FonteRequest request) {
        validar(request);

        String sigla = request.sigla().trim().toUpperCase();
        if (repository.existsBySiglaIgnoreCase(sigla)) {
            throw new SiglaDuplicadaException(request.sigla().trim());
        }

        Fonte fonte = new Fonte();
        fonte.setNome(request.nome().trim());
        fonte.setSigla(sigla);
        fonte.setOrgao(request.orgao().trim());
        fonte.setFormato(request.formato().trim());
        fonte.setPeriodicidade(vazio(request.periodicidade()) ? "eventual" : request.periodicidade().trim());

        // --- Novos campos mapeados (Task T1 - Ingestão por Link e Execução) ---
        fonte.setModalidade(vazio(request.modalidade()) ? "MANUAL" : request.modalidade().trim().toUpperCase());
        fonte.setUrlOrigem(request.urlOrigem() != null ? request.urlOrigem().trim() : null);
        fonte.setMetodoHttp(vazio(request.metodoHttp()) ? "GET" : request.metodoHttp().trim().toUpperCase());
        fonte.setCabecalhos(request.cabecalhos());
        fonte.setCredencialRef(request.credencialRef() != null ? request.credencialRef().trim() : null);
        fonte.setCorpoRequisicao(request.corpoRequisicao());
        fonte.setPaginacao(request.paginacao() != null ? request.paginacao().trim() : null);
        fonte.setCron(request.cron() != null ? request.cron().trim() : null);

        return repository.save(fonte);
    }

    @Override
    public Fonte buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new FonteNaoEncontradaException(id));
    }

    private void validar(FonteRequest request) {
        List<String> faltando = new ArrayList<>();
        if (vazio(request.nome()))    faltando.add("nome");
        if (vazio(request.sigla()))   faltando.add("sigla");
        if (vazio(request.orgao()))   faltando.add("orgao");
        if (vazio(request.formato())) faltando.add("formato");
        if (!faltando.isEmpty()) {
            throw new RequisicaoInvalidaException("Preencha: " + String.join(", ", faltando) + ".", faltando);
        }

        if (request.sigla().trim().length() > SIGLA_MAX) {
            throw new RequisicaoInvalidaException(
                    "A sigla deve ter no máximo " + SIGLA_MAX + " caracteres.", List.of("sigla"));
        }

        if (!FORMATOS.contains(request.formato().trim())) {
            throw new RequisicaoInvalidaException(
                    "Formato inválido. Use: " + String.join(", ", FORMATOS) + ".", List.of("formato"));
        }
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String executarIngestao(Long id) {
        Fonte fonte = buscarPorId(id);

        if (fonte.getUrlOrigem() == null || fonte.getUrlOrigem().isBlank()) {
            throw new RequisicaoInvalidaException("A fonte selecionada não possui uma URL de origem configurada.", List.of("urlOrigem"));
        }

        try {
            java.net.URL url = new java.net.URL(fonte.getUrlOrigem());
            javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection) url.openConnection();

            // Configura TrustManager para ignorar barreiras estritas de certificado em ambiente de governo/dev
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };

            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);

            String metodo = "POST".equalsIgnoreCase(fonte.getMetodoHttp()) ? "POST" : "GET";
            conn.setRequestMethod(metodo);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            if ("POST".equals(metodo)) {
                conn.setDoOutput(true);
                String corpo = fonte.getCorpoRequisicao() != null ? fonte.getCorpoRequisicao() : "";
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = corpo.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }

            int responseCode = conn.getResponseCode();
            
            // Lê o conteúdo da resposta (ou stream de erro se houver)
            java.io.InputStream inputStream = (responseCode >= 200 && responseCode < 300) 
                    ? conn.getInputStream() 
                    : conn.getErrorStream();

            String respostaTexto = "";
            if (inputStream != null) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    respostaTexto = sb.toString();
                }
            }

            if (responseCode >= 200 && responseCode < 300) {
                return "Ingestão executada com sucesso! Status HTTP: " + responseCode 
                       + " | Tamanho da resposta: " + respostaTexto.length() + " caracteres.";
            } else {
                throw new RuntimeException("O servidor externo respondeu com erro. Status HTTP: " + responseCode + " - " + respostaTexto);
            }

        } catch (Exception e) {
            throw new RuntimeException("Falha ao executar a ingestão da fonte: " + e.getMessage());
        }
    }
}