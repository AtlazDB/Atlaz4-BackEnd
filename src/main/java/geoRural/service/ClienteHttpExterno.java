package geoRural.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.exception.ColetaFalhouException;
import geoRural.exception.RequisicaoInvalidaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As requisições que o servidor faz a links digitados na tela (coleta e "Testar link"). Concentra
 * o que as duas precisam fazer igual: conferir cada destino com o ValidadorUrlExterna (inclusive
 * os redirecionamentos, que por isso são seguidos à mão), mandar os cabeçalhos da fonte e
 * transformar falha do servidor de origem em mensagem para o operador.
 */
@Component
public class ClienteHttpExterno {

    private static final int MAX_REDIRECIONAMENTOS = 5;

    private final ValidadorUrlExterna validador;
    private final ObjectMapper objectMapper;
    private final HttpClient cliente;
    private final Duration esperaResposta;

    public ClienteHttpExterno(ValidadorUrlExterna validador, ObjectMapper objectMapper,
                              @Value("${app.coleta.espera-resposta-segundos:120}") long esperaRespostaSegundos) {
        this.validador = validador;
        this.objectMapper = objectMapper;
        this.esperaResposta = Duration.ofSeconds(esperaRespostaSegundos);
        this.cliente = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** O que vai na requisição: vem da fonte cadastrada ou do formulário ("Testar link"). */
    public record Pedido(String url, String metodoHttp, String cabecalhos, String corpo) {}

    /**
     * Faz a requisição e devolve a resposta 2xx, com o corpo ainda por ler (quem chama fecha).
     * {@code HttpResponse.uri()} é o endereço final, depois dos redirecionamentos.
     */
    public HttpResponse<InputStream> abrir(Pedido pedido) throws IOException, InterruptedException {
        URI uri = validador.validar(pedido.url());
        String metodo = "POST".equalsIgnoreCase(pedido.metodoHttp()) ? "POST" : "GET";
        Map<String, String> cabecalhos = cabecalhos(pedido.cabecalhos());

        for (int saltos = 0; ; saltos++) {
            HttpResponse<InputStream> resposta;
            try {
                resposta = cliente.send(requisicao(uri, metodo, cabecalhos, pedido.corpo()),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new ColetaFalhouException("O servidor de origem demorou demais para responder.", e);
            } catch (IOException e) {
                throw new ColetaFalhouException("Não foi possível falar com o servidor de origem ("
                        + uri.getHost() + "): " + e.getMessage(), e);
            }
            int codigo = resposta.statusCode();
            Optional<String> destino = resposta.headers().firstValue("Location");

            if (codigo >= 300 && codigo < 400 && destino.isPresent()) {
                resposta.body().close();
                if (saltos >= MAX_REDIRECIONAMENTOS) {
                    throw new ColetaFalhouException("O link redirecionou mais de " + MAX_REDIRECIONAMENTOS + " vezes.");
                }
                uri = validador.validar(uri.resolve(destino.get()).toString());
                // 301, 302 e 303 viram GET, como fazem os navegadores; 307 e 308 mantêm o método.
                metodo = (codigo == 307 || codigo == 308) ? metodo : "GET";
                continue;
            }
            if (codigo < 200 || codigo >= 300) {
                resposta.body().close();
                throw new ColetaFalhouException("O servidor de origem respondeu HTTP " + codigo + ".");
            }
            return resposta;
        }
    }

    private HttpRequest requisicao(URI uri, String metodo, Map<String, String> cabecalhos, String corpo) {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(uri)
                .timeout(esperaResposta)
                // Alguns servidores do governo recusam o agente padrão do Java.
                .header("User-Agent", "GeoRural-DataHub/1.0 (coleta de dados abertos)");
        for (Map.Entry<String, String> cabecalho : cabecalhos.entrySet()) {
            try {
                pedido.header(cabecalho.getKey(), cabecalho.getValue());
            } catch (IllegalArgumentException e) {
                throw new RequisicaoInvalidaException(
                        "O cabeçalho \"" + cabecalho.getKey() + "\" não pode ser enviado.", List.of("cabecalhos"));
            }
        }
        if ("POST".equals(metodo)) {
            return pedido.POST(HttpRequest.BodyPublishers.ofString(corpo == null ? "" : corpo, StandardCharsets.UTF_8))
                    .build();
        }
        return pedido.GET().build();
    }

    private Map<String, String> cabecalhos(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new RequisicaoInvalidaException(
                    "Os cabeçalhos precisam ser um objeto JSON, como {\"Accept\": \"application/json\"}.",
                    List.of("cabecalhos"));
        }
    }
}
