package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import geoRural.dto.ColetaResponse;
import geoRural.entity.Fonte;
import geoRural.exception.ColetaFalhouException;
import geoRural.exception.RequisicaoInvalidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contra o Oracle do docker-compose, com um servidor HTTP local no papel do órgão de origem.
 * O validador é simulado só para deixar passar esse servidor local (que ele recusa de
 * propósito); a trava em si tem o próprio teste (ValidadorUrlExternaTest).
 */
@SpringBootTest(properties = "app.processamento.airflow=false")  // a coleta Java, mesmo com o Airflow ligado no .env
@Transactional
class ColetaLinkServiceTest {

    @MockitoBean
    private ObjectStorage objectStorage;

    @MockitoBean
    private ValidadorUrlExterna validador;

    @Autowired
    private ColetaLinkService coleta;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer servidor;
    private String base;
    private volatile String conteudo = "id,lat,lon\n1,-25.1,-50.1\n";

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/focos.csv", troca -> responder(troca, 200, conteudo, "text/csv; charset=utf-8"));
        servidor.createContext("/velho", troca -> {
            troca.getResponseHeaders().add("Location", "/focos.csv");
            troca.sendResponseHeaders(302, -1);
            troca.close();
        });
        servidor.createContext("/sumiu", troca -> responder(troca, 404, "não achei", "text/plain"));
        // O GeoServer corta no limite por pedido e avisa no começo do JSON; o ArcGIS avisa no fim.
        servidor.createContext("/wfs", troca -> responder(troca, 200,
                "{\"type\":\"FeatureCollection\",\"numberMatched\":558443,\"numberReturned\":10000,\"features\":[]}",
                "application/json"));
        servidor.createContext("/arcgis", troca -> responder(troca, 200,
                "{\"type\":\"FeatureCollection\",\"features\":[],\"properties\":{\"exceededTransferLimit\":true}}",
                "application/json"));
        servidor.createContext("/wfs-completo", troca -> responder(troca, 200,
                "{\"type\":\"FeatureCollection\",\"numberMatched\":2,\"numberReturned\":2,\"features\":[]}",
                "application/json"));
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        when(validador.validar(anyString())).thenAnswer(chamada -> URI.create(chamada.getArgument(0)));
    }

    @AfterEach
    void derrubarServidor() {
        servidor.stop(0);
    }

    @Test
    void coletaGuardaNaZonaBrutaEDepoisDizSemMudanca() {
        long fonteId = fonte(base + "/focos.csv");

        ColetaResponse primeira = coleta.executar(fonteId);
        assertThat(primeira.resultado()).isEqualTo("RECEBIDO");
        assertThat(primeira.arquivo().nome()).isEqualTo("focos.csv");
        assertThat(primeira.arquivo().status()).isEqualTo("RECEBIDO");
        assertThat(primeira.arquivo().origem()).isEqualTo("COLETA");
        verify(objectStorage, times(1)).putObject(any(PutObjectRequest.class));

        ColetaResponse segunda = coleta.executar(fonteId);
        assertThat(segunda.resultado()).isEqualTo("SEM_MUDANCA");
        assertThat(segunda.arquivo().id()).isEqualTo(primeira.arquivo().id());
        verify(objectStorage, times(1)).putObject(any(PutObjectRequest.class));  // não subiu de novo

        conteudo = conteudo + "2,-25.2,-50.2\n";
        assertThat(coleta.executar(fonteId).resultado()).isEqualTo("RECEBIDO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM arquivo_bruto WHERE fonte_id = ?",
                Integer.class, fonteId)).isEqualTo(2);
    }

    @Test
    void segueORedirecionamentoConferindoODestino() {
        long fonteId = fonte(base + "/velho");

        assertThat(coleta.executar(fonteId).arquivo().nome()).isEqualTo("focos.csv");
        verify(validador).validar(base + "/focos.csv");  // o destino também passa pela trava
    }

    @Test
    void erroDoServidorDeOrigemNaoGravaNada() {
        long fonteId = fonte(base + "/sumiu");

        assertThatThrownBy(() -> coleta.executar(fonteId))
                .isInstanceOf(ColetaFalhouException.class)
                .hasMessage("O servidor de origem respondeu HTTP 404.");
        verify(objectStorage, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void recusaRespostaCortadaNoLimitePorPedido() {
        long wfs = fonte(base + "/wfs");
        assertThatThrownBy(() -> coleta.executar(wfs))
                .isInstanceOf(ColetaFalhouException.class)
                .hasMessageStartingWith("O serviço entregou 10000 de 558443 registros");

        jdbc.update("DELETE FROM fonte WHERE id = ?", wfs);
        long arcgis = fonte(base + "/arcgis");
        assertThatThrownBy(() -> coleta.executar(arcgis))
                .isInstanceOf(ColetaFalhouException.class)
                .hasMessageStartingWith("O serviço ArcGIS cortou a resposta");
        verify(objectStorage, never()).putObject(any(PutObjectRequest.class));

        jdbc.update("DELETE FROM fonte WHERE id = ?", arcgis);
        assertThat(coleta.executar(fonte(base + "/wfs-completo")).resultado()).isEqualTo("RECEBIDO");
    }

    @Test
    void comOAirflowLigadoSoRegistraOPedido() {
        Fonte fonte = new Fonte();
        fonte.setUrlOrigem(base + "/focos.csv");
        FonteService fontes = org.mockito.Mockito.mock(FonteService.class);
        when(fontes.buscarPorId(7L)).thenReturn(fonte);
        geoRural.repository.FonteRepository repositorio = org.mockito.Mockito.mock(geoRural.repository.FonteRepository.class);
        ColetaLinkServiceImpl comAirflow = new ColetaLinkServiceImpl(fontes, null, null, null, repositorio, 1024, true);

        ColetaResponse resposta = comAirflow.executar(7L);

        assertThat(resposta.resultado()).isEqualTo("SOLICITADA");
        assertThat(fonte.getColetaSolicitadaEm()).isNotNull();
        verify(repositorio).save(fonte);
        verify(objectStorage, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void fonteSemLinkERecusada() {
        long fonteId = fonte(null);

        assertThatThrownBy(() -> coleta.executar(fonteId)).isInstanceOf(RequisicaoInvalidaException.class);
    }

    @Test
    void nomeVemDoServidorDoLinkOuDoFormato() {
        Fonte fonte = new Fonte();
        fonte.setSigla("CAR");
        fonte.setFormato("SHP");
        URI wfs = URI.create("https://geoserver.car.gov.br/geoserver/sicar/ows?service=WFS&outputFormat=SHAPE-ZIP");

        assertThat(ColetaLinkServiceImpl.nomeDoArquivo(wfs, "attachment; filename=sicar_imoveis_pr.zip",
                "application/zip", fonte)).isEqualTo("sicar_imoveis_pr.zip");
        assertThat(ColetaLinkServiceImpl.nomeDoArquivo(URI.create("https://x.gov.br/a/focos_mensal_br_202401.csv"),
                null, "text/csv", fonte)).isEqualTo("focos_mensal_br_202401.csv");
        assertThat(ColetaLinkServiceImpl.nomeDoArquivo(wfs, null, "application/zip", fonte))
                .startsWith("car_").endsWith(".zip");
    }

    private long fonte(String url) {
        jdbc.update("""
                INSERT INTO fonte (nome, sigla, orgao, formato, modalidade, url_origem)
                VALUES ('Fonte de teste da coleta', 'TESTE_COL', 'Teste', 'CSV', 'LINK', ?)""", url);
        return jdbc.queryForObject("SELECT id FROM fonte WHERE sigla = 'TESTE_COL'", Long.class);
    }

    private static void responder(HttpExchange troca, int codigo, String corpo, String tipo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", tipo);
        troca.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream out = troca.getResponseBody()) {
            out.write(bytes);
        }
    }
}
