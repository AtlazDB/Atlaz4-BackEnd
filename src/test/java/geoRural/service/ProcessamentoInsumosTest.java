package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.dto.ProcessamentoResponse;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/**
 * Processamento da Reserva Legal e dos focos de ponta a ponta contra o Oracle, com o bucket
 * simulado: o arquivo "baixado" é gerado aqui. Sem @Transactional — o processamento grava em
 * transações próprias —, então a limpeza é manual.
 */
@SpringBootTest
class ProcessamentoInsumosTest {

    private static final String IMOVEL = "TESTE-RL-PROC-1";
    private static final List<String> FOCOS = List.of("teste-foco-1", "teste-foco-2", "teste-foco-3",
            "teste-foco-4", "teste-foco-5");

    @MockitoBean
    private ObjectStorage objectStorage;

    @MockitoBean
    private ObjectStorageService objectStorageService;

    @Autowired
    private ProcessamentoArquivoService processamento;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tmp;

    private final List<Long> arquivos = new ArrayList<>();

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM car_reserva_legal WHERE cod_imovel = ?", IMOVEL);
        for (String id : FOCOS) {
            jdbc.update("DELETE FROM foco_calor WHERE id_inpe = ?", id);
        }
        for (Long arquivo : arquivos) {
            String execucoes = "SELECT id FROM execucao WHERE arquivo_bruto_id = " + arquivo;
            jdbc.update("DELETE FROM registro_quarentena WHERE execucao_id IN (" + execucoes + ")");
            jdbc.update("DELETE FROM dataset_versao WHERE execucao_id IN (" + execucoes + ")");
            jdbc.update("DELETE FROM execucao WHERE arquivo_bruto_id = ?", arquivo);
            jdbc.update("DELETE FROM arquivo_bruto WHERE id = ?", arquivo);
        }
    }

    // ------------------------------------------------------------------ Reserva Legal

    @Test
    void reservaLegalGravaOsPoligonosValidosEQuarentenaOResto() throws Exception {
        Path zip = shapefileRl("rl", List.of(
                linhaRl(IMOVEL, 8.5, -40.10, -30.10),
                linhaRl(IMOVEL, 3.0, -40.08, -30.10),     // mesmo imóvel, segundo polígono: vale
                linhaRl(IMOVEL, -1.0, -40.06, -30.10)));  // área negativa: quarentena
        long arquivo = arquivo("CAR-RL", "RESERVA_LEGAL_TESTE.zip", zip);

        ProcessamentoResponse resposta = processamento.processar(arquivo);

        assertThat(resposta.status()).isEqualTo("PROCESSADO");
        assertThat(resposta.registrosLidos()).isEqualTo(3);
        assertThat(resposta.registrosValidos()).isEqualTo(2);
        assertThat(resposta.registrosInvalidos()).isEqualTo(1);
        assertThat(soma("SELECT SUM(area_ha) FROM car_reserva_legal WHERE cod_imovel = ?", IMOVEL)).isEqualTo(11.5);
        assertThat(jdbc.queryForObject("""
                SELECT motivo FROM registro_quarentena
                 WHERE execucao_id = (SELECT id FROM execucao WHERE arquivo_bruto_id = ?)""",
                String.class, arquivo)).isEqualTo("num_area negativo");
    }

    @Test
    void cargaNovaSubstituiARlAnteriorDoImovel() throws Exception {
        processamento.processar(arquivo("CAR-RL", "rl_v1.zip",
                shapefileRl("rl1", List.of(linhaRl(IMOVEL, 8.5, -40.10, -30.10), linhaRl(IMOVEL, 3.0, -40.08, -30.10)))));
        processamento.processar(arquivo("CAR-RL", "rl_v2.zip",
                shapefileRl("rl2", List.of(linhaRl(IMOVEL, 20.0, -40.10, -30.10)))));

        // Só o polígono da carga nova fica: 20 ha, e não 8,5 + 3 + 20
        assertThat(soma("SELECT SUM(area_ha) FROM car_reserva_legal WHERE cod_imovel = ?", IMOVEL)).isEqualTo(20.0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM car_reserva_legal WHERE cod_imovel = ?",
                Integer.class, IMOVEL)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Focos

    @Test
    void focosFicaSoComOParanaEQuarentenaOQueEstaComDefeito() throws Exception {
        long arquivo = arquivo("INPE-FOCOS", "focos_teste.csv", csv(CSV_FOCOS));

        ProcessamentoResponse resposta = processamento.processar(arquivo);

        assertThat(resposta.status()).isEqualTo("PROCESSADO");
        assertThat(resposta.registrosLidos()).isEqualTo(5);
        assertThat(resposta.registrosValidos()).isEqualTo(2);
        assertThat(resposta.registrosInvalidos()).isEqualTo(2);
        assertThat(resposta.registrosForaDoRecorte()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id_inpe FROM foco_calor WHERE id_inpe LIKE 'teste-foco-%' ORDER BY id_inpe",
                String.class)).containsExactly("teste-foco-1", "teste-foco-2");

        List<Map<String, Object>> quarentena = jdbc.queryForList("""
                SELECT q.chave_natural, q.entidade, q.tipo_erro, q.valor_recebido, q.dataset_versao_id,
                       CASE WHEN q.geometria IS NULL THEN 0 ELSE 1 END tem_geometria
                  FROM registro_quarentena q JOIN execucao e ON e.id = q.execucao_id
                 WHERE e.arquivo_bruto_id = ? ORDER BY q.chave_natural""", arquivo);
        assertThat(quarentena).extracting(l -> l.get("CHAVE_NATURAL"), l -> l.get("TIPO_ERRO"), l -> l.get("VALOR_RECEBIDO"))
                .containsExactly(
                        tuple("teste-foco-4", "TIPO_DE_DADO_INVALIDO", "10/09/2026"),
                        tuple("teste-foco-5", "FORA_DO_RECORTE_TERRITORIAL", "lat=-12.000000, lon=-50.130000"));
        assertThat(quarentena).allSatisfy(l -> {
            assertThat(l.get("ENTIDADE")).isEqualTo("foco_calor");
            assertThat(l.get("DATASET_VERSAO_ID")).isNotNull();
            assertThat(((Number) l.get("TEM_GEOMETRIA")).intValue()).isEqualTo(1);
        });
    }

    @Test
    void recarregarOMesmoMesCompactadoNaoDuplicaFocos() throws Exception {
        processamento.processar(arquivo("INPE-FOCOS", "focos_teste.csv", csv(CSV_FOCOS)));
        long segundo = arquivo("INPE-FOCOS", "focos_teste.zip", zip("focos_teste.csv", CSV_FOCOS));

        ProcessamentoResponse resposta = processamento.processar(segundo);

        assertThat(resposta.status()).isEqualTo("PROCESSADO");
        assertThat(resposta.registrosValidos()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM foco_calor WHERE id_inpe LIKE 'teste-foco-%'",
                Integer.class)).isEqualTo(2);
        // A versão nova não trouxe foco nenhum
        assertThat(jdbc.queryForObject("""
                SELECT v.registros FROM dataset_versao v JOIN execucao e ON e.id = v.execucao_id
                 WHERE e.arquivo_bruto_id = ?""", Integer.class, segundo)).isZero();
    }

    @Test
    void fonteSemProcessamentoEhRecusada() throws Exception {
        long arquivo = arquivo("IBAMA", "embargos.csv", csv(CSV_FOCOS));

        assertThatThrownBy(() -> processamento.processar(arquivo))
                .hasMessageContaining("CAR, CAR-RL e INPE-FOCOS");
    }

    private static final String CSV_FOCOS = """
            id,lat,lon,data_hora_gmt,satelite,municipio,estado,pais,municipio_id,estado_id,pais_id,numero_dias_sem_chuva,precipitacao,risco_fogo,bioma,frp
            teste-foco-1, -25.100000, -50.130000,2026-09-10 15:12:00,AQUA_M-T,PONTA GROSSA,PARANÁ,Brasil,4119905,41,33,10,0.0,0.8,Mata Atlântica,12.4
            teste-foco-2, -25.110000, -50.120000,2026-09-10 04:30:00,NOAA-20,PONTA GROSSA,PARANÁ,Brasil,4119905,41,33,10,0.0,0.8,Mata Atlântica,
            teste-foco-3, -12.970000, -41.220000,2026-09-10 15:12:00,AQUA_M-T,"LENÇÓIS, BA",BAHIA,Brasil,2919306,29,33,10,0.0,0.8,Caatinga,30.0
            teste-foco-4, -25.100000, -50.130000,10/09/2026,AQUA_M-T,PONTA GROSSA,PARANÁ,Brasil,4119905,41,33,10,0.0,0.8,Mata Atlântica,1.0
            teste-foco-5, -12.000000, -50.130000,2026-09-10 15:12:00,AQUA_M-T,PONTA GROSSA,PARANÁ,Brasil,4119905,41,33,10,0.0,0.8,Mata Atlântica,1.0
            """;

    // ------------------------------------------------------------------ apoio

    private long arquivo(String sigla, String nome, Path conteudo) throws IOException {
        Long fonte = jdbc.queryForObject("SELECT id FROM fonte WHERE UPPER(sigla) = ?", Long.class, sigla);
        String chave = "bruta/" + sigla + "/teste/" + UUID.randomUUID() + "_" + nome;
        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256, status)
                VALUES (?, ?, ?, ?, ?, 'RECEBIDO')""", fonte, chave, nome, Files.size(conteudo), "0".repeat(64));
        long id = jdbc.queryForObject("SELECT id FROM arquivo_bruto WHERE chave_objeto = ?", Long.class, chave);
        arquivos.add(id);

        // O processamento apaga o arquivo baixado no fim: cada chamada recebe uma cópia
        when(objectStorageService.baixar(chave)).thenAnswer(inv -> {
            Path copia = Files.createTempFile(tmp, "baixado-", ".zip");
            Files.copy(conteudo, copia, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return copia;
        });
        return id;
    }

    private record LinhaRl(String cod, double areaHa, double x, double y) {}

    private static LinhaRl linhaRl(String cod, double areaHa, double x, double y) {
        return new LinhaRl(cod, areaHa, x, y);
    }

    /** Gera um shapefile de RL com as colunas do SICAR (cod_imovel, municipio, num_area, ind_status) e zipa. */
    private Path shapefileRl(String nome, List<LinhaRl> linhas) throws IOException {
        Path pasta = Files.createDirectories(tmp.resolve(nome));
        SimpleFeatureTypeBuilder tipo = new SimpleFeatureTypeBuilder();
        tipo.setName("RESERVA_LEGAL");
        tipo.setCRS(DefaultGeographicCRS.WGS84);
        tipo.add("the_geom", MultiPolygon.class);
        tipo.length(80).add("cod_imovel", String.class);
        tipo.length(60).add("municipio", String.class);
        tipo.add("num_area", Double.class);
        tipo.length(4).add("ind_status", String.class);
        SimpleFeatureType schema = tipo.buildFeatureType();

        Path shp = pasta.resolve("RESERVA_LEGAL.shp");
        ShapefileDataStore store = (ShapefileDataStore) new ShapefileDataStoreFactory()
                .createNewDataStore(Map.<String, Serializable>of("url", shp.toUri().toURL()));
        store.createSchema(schema);

        GeometryFactory fabrica = new GeometryFactory();
        List<SimpleFeature> features = new ArrayList<>();
        int i = 0;
        for (LinhaRl l : linhas) {
            Polygon quadrado = fabrica.createPolygon(new Coordinate[]{
                    new Coordinate(l.x(), l.y()), new Coordinate(l.x(), l.y() + 0.01),
                    new Coordinate(l.x() + 0.01, l.y() + 0.01), new Coordinate(l.x() + 0.01, l.y()),
                    new Coordinate(l.x(), l.y())});
            features.add(SimpleFeatureBuilder.build(schema, new Object[]{
                    fabrica.createMultiPolygon(new Polygon[]{quadrado}), l.cod(), "Municipio Teste", l.areaHa(), "AT"},
                    "rl." + (++i)));
        }
        try (DefaultTransaction transacao = new DefaultTransaction("criar")) {
            SimpleFeatureStore fonte = (SimpleFeatureStore) store.getFeatureSource("RESERVA_LEGAL");
            fonte.setTransaction(transacao);
            fonte.addFeatures(DataUtilities.collection(features));
            transacao.commit();
        } finally {
            store.dispose();
        }

        Path zip = tmp.resolve(nome + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
             var arquivos = Files.list(pasta)) {
            for (Path p : arquivos.toList()) {
                out.putNextEntry(new ZipEntry(p.getFileName().toString()));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
        return zip;
    }

    private Path csv(String conteudo) throws IOException {
        Path p = Files.createTempFile(tmp, "focos-", ".csv");
        Files.writeString(p, conteudo, StandardCharsets.UTF_8);
        return p;
    }

    private Path zip(String nomeInterno, String conteudo) throws IOException {
        Path p = Files.createTempFile(tmp, "focos-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new ZipEntry(nomeInterno));
            out.write(conteudo.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return p;
    }

    private Double soma(String sql, Object... args) {
        return jdbc.queryForObject(sql, Double.class, args);
    }
}
