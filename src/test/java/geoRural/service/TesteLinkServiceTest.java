package geoRural.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import geoRural.dto.TesteLinkRequest;
import geoRural.dto.TesteLinkResponse;
import geoRural.exception.ColetaFalhouException;
import geoRural.exception.RequisicaoInvalidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sem Spring e sem banco: um servidor HTTP local faz o papel dos serviços do governo (ArcGIS do
 * IAT, GeoServer do SICAR, CSV do INPE). O validador é simulado só para aceitar esse servidor
 * local, que ele recusa de propósito (ver ValidadorUrlExternaTest).
 */
class TesteLinkServiceTest {

    private HttpServer servidor;
    private String base;
    private final List<String> pedidos = new ArrayList<>();
    private TesteLinkService teste;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // ArcGIS: 333.604 focos, 2.000 por pedido (como o serviço de focos do IAT)
        servidor.createContext("/arcgis/rest/services/Focos/FeatureServer/0", troca -> {
            String consulta = troca.getRequestURI().getRawQuery();
            if (troca.getRequestURI().getPath().endsWith("/query") && consulta.contains("returnCountOnly=true")) {
                responder(troca, "{\"count\":333604}", "application/json");
            } else if (troca.getRequestURI().getPath().endsWith("/query")) {
                responder(troca, """
                        {"type":"FeatureCollection","features":[
                          {"type":"Feature","properties":{"datahora":"2026-08-01","satelite":"AQUA_M-T","municipio":"Bituruna"},
                           "geometry":{"type":"Point","coordinates":[-51.5,-26.1]}}]}""", "application/geo+json");
            } else {
                responder(troca, "{\"name\":\"Focos\",\"maxRecordCount\":2000}", "application/json");
            }
        });
        // GeoServer WFS: total pelo resultType=hits, amostra em JSON
        servidor.createContext("/geoserver/sicar/ows", troca -> {
            String consulta = troca.getRequestURI().getRawQuery();
            if (consulta.contains("resultType=hits")) {
                responder(troca, "<wfs:FeatureCollection numberMatched=\"558322\" numberReturned=\"0\"/>", "text/xml");
            } else {
                responder(troca, """
                        {"type":"FeatureCollection","features":[{"type":"Feature",
                          "properties":{"cod_imovel":"PR-4101903-E8F3","status_imovel":"AT","area":109.73},
                          "geometry":null}]}""", "application/json");
            }
        });
        servidor.createContext("/dados/focos.csv", troca ->
                responder(troca, "id;lat;lon;data_hora_gmt\n1;-25.1;-50.1;2026-09-10\n", "text/csv"));
        servidor.createContext("/dados/biomas.zip", troca ->
                responder(troca, "PK\u0003\u0004 conteúdo de mentira", "application/zip"));
        servidor.createContext("/sumiu", troca -> {
            troca.sendResponseHeaders(404, -1);
            troca.close();
        });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();

        ValidadorUrlExterna validador = mock(ValidadorUrlExterna.class);
        when(validador.validar(anyString())).thenAnswer(chamada -> URI.create(chamada.getArgument(0)));
        teste = new TesteLinkServiceImpl(new ClienteHttpExterno(validador, new ObjectMapper(), 30), new ObjectMapper());
    }

    @AfterEach
    void derrubarServidor() {
        servidor.stop(0);
    }

    @Test
    void arcgisTrazTotalLimiteEColunasEAvisaQueNaoCabeNumPedido() {
        TesteLinkResponse r = teste.testar(pedido(base
                + "/arcgis/rest/services/Focos/FeatureServer/0/query?where=1%3D1&outFields=*&f=geojson&outSR=4674"));

        assertThat(r.tipo()).isEqualTo("SERVICO_ARCGIS");
        assertThat(r.total()).isEqualTo(333604L);
        assertThat(r.limitePorPedido()).isEqualTo(2000);
        assertThat(r.colunas()).containsExactly("datahora", "satelite", "municipio");
        assertThat(r.avisos()).singleElement().asString().contains("333.604", "2.000");
        assertThat(r.mensagem()).isEqualTo("O link respondeu (HTTP 200) · 333.604 registros · 3 colunas.");
        // a amostra pediu só 5, e o filtro original (where) foi mantido
        assertThat(pedidos).anyMatch(p -> p.contains("resultRecordCount=5") && p.contains("where=1%3D1"));
    }

    @Test
    void wfsTrazTotalPeloHitsEColunasDaAmostra() {
        TesteLinkResponse r = teste.testar(pedido(base
                + "/geoserver/sicar/ows?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr"
                + "&outputFormat=application/json"));

        assertThat(r.tipo()).isEqualTo("SERVICO_WFS");
        assertThat(r.total()).isEqualTo(558322L);
        assertThat(r.colunas()).containsExactly("cod_imovel", "status_imovel", "area");
        assertThat(pedidos).anyMatch(p -> p.contains("count=5"));
    }

    @Test
    void csvTrazAsColunasDoCabecalho() {
        TesteLinkResponse r = teste.testar(pedido(base + "/dados/focos.csv"));

        assertThat(r.tipo()).isEqualTo("ARQUIVO");
        assertThat(r.total()).isNull();
        assertThat(r.colunas()).containsExactly("id", "lat", "lon", "data_hora_gmt");
    }

    @Test
    void zipAvisaQueAsColunasVemDepois() {
        TesteLinkResponse r = teste.testar(pedido(base + "/dados/biomas.zip"));

        assertThat(r.colunas()).isEmpty();
        assertThat(r.avisos()).singleElement().asString().contains("compactado");
    }

    @Test
    void linkQueNaoExisteFalhaComOStatusDoServidor() {
        assertThatThrownBy(() -> teste.testar(pedido(base + "/sumiu")))
                .isInstanceOf(ColetaFalhouException.class)
                .hasMessage("O servidor de origem respondeu HTTP 404.");
    }

    @Test
    void semLinkERecusado() {
        assertThatThrownBy(() -> teste.testar(pedido("  "))).isInstanceOf(RequisicaoInvalidaException.class);
    }

    @Test
    void parametrosTrocadosSemMexerNoResto() {
        assertThat(TesteLinkServiceImpl.comParametros("https://x/q?where=a%3D1&RESULTRECORDCOUNT=99&f=geojson",
                Map.of("resultRecordCount", "5"), Set.of("f")))
                .isEqualTo("https://x/q?where=a%3D1&resultRecordCount=5");
    }

    private TesteLinkRequest pedido(String url) {
        return new TesteLinkRequest(url, "GET", null, null);
    }

    private void responder(HttpExchange troca, String corpo, String tipo) throws IOException {
        synchronized (pedidos) {
            pedidos.add(troca.getRequestURI().toString());
        }
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", tipo);
        troca.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = troca.getResponseBody()) {
            out.write(bytes);
        }
    }
}
