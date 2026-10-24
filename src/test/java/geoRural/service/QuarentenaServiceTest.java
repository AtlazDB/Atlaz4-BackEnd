package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.dto.CorrecaoRequestDTO;
import geoRural.dto.DescarteRequestDTO;
import geoRural.dto.GeometryCorrectionRequest;
import geoRural.dto.QuarentenaFiltroDTO;
import geoRural.dto.RevalidationError;
import geoRural.dto.RevalidationResponse;
import geoRural.exception.GravacaoNaoPermitidaException;
import geoRural.exception.QuarantineClosedException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.RegistroQuarentenaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contra o Oracle do docker-compose, com o pkg_quality de verdade. Cada teste é desfeito no
 * fim (@Transactional), e o revalidar_registro só usa SAVEPOINT, nunca COMMIT.
 */
@SpringBootTest
@Transactional
class QuarentenaServiceTest {

    private static final String ARQUIVO = "teste/quarentena/imoveis.zip";

    /** O cliente da OCI: evita exigir ~/.oci/config. */
    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private QuarentenaService service;

    @Autowired
    private RegistroQuarentenaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long fonteId;
    private long execucaoId;

    /** Uma fonte com uma regra só: cod_imovel é obrigatório. */
    @BeforeEach
    void montarCenario() {
        jdbc.update("""
                INSERT INTO fonte (nome, sigla, orgao, formato)
                VALUES ('Fonte de teste da quarentena', 'TESTE_Q', 'Teste', 'CSV')""");
        fonteId = jdbc.queryForObject("SELECT id FROM fonte WHERE sigla = 'TESTE_Q'", Long.class);

        jdbc.update("INSERT INTO fonte_coluna (fonte_id, nome_coluna) VALUES (?, 'cod_imovel')", fonteId);
        jdbc.update("""
                INSERT INTO regra_campo
                  (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado, obrigatorio, chave)
                SELECT ?, id, 'imovel_rural', 'cod_imovel', 'TEXTO', 1, 1
                  FROM fonte_coluna WHERE fonte_id = ?""", fonteId, fonteId);

        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256)
                VALUES (?, ?, 'imoveis.zip', 1, 'teste')""", fonteId, ARQUIVO);
        jdbc.update("""
                INSERT INTO execucao (arquivo_bruto_id, tipo)
                SELECT id, 'VALIDACAO' FROM arquivo_bruto WHERE chave_objeto = ?""", ARQUIVO);
        execucaoId = jdbc.queryForObject("""
                SELECT e.id FROM execucao e
                  JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id
                 WHERE a.chave_objeto = ?""", Long.class, ARQUIVO);
    }

    // ----- revalidar ------------------------------------------------------------

    @Test
    void revalidarReprovadoMantemOStatus() {
        long id = quarentena("{\"area_ha\": \"12.5\"}");

        RevalidationResponse resultado = service.revalidar(id);

        assertThat(resultado.status()).isEqualTo("REPROVADO");
        assertThat(resultado.errors()).extracting(RevalidationError::errorType)
                .containsExactly("CAMPO_OBRIGATORIO_AUSENTE");
        assertThat(resultado.quarantine().getStatusAnalise()).isEqualTo("PENDENTE");
    }

    /** Aprovado: o pkg_tratada grava o imóvel na Tratada, na versão da carga, e encerra o registro. */
    @Test
    void revalidarAprovadoGravaNaTratada() {
        mapearGeometria();
        jdbc.update("""
                INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona)
                VALUES ('teste_quarentena', 1, ?, 'TRATADA')""", execucaoId);
        long versaoId = jdbc.queryForObject(
                "SELECT id FROM dataset_versao WHERE execucao_id = ?", Long.class, execucaoId);
        long id = quarentenaComGeometria("{\"area_ha\": \"12.5\"}");
        service.corrigir(id, new CorrecaoRequestDTO("{\"cod_imovel\": \"PR-TESTE-Q-001\"}", "Faltava o código."));
        // Na tela são duas requisições; aqui é uma transação só, e o pacote lê do banco.
        repository.flush();

        RevalidationResponse resultado = service.revalidar(id);

        assertThat(resultado.status()).isEqualTo("APROVADO");
        assertThat(resultado.errors()).isEmpty();
        assertThat(resultado.quarantine().getStatusAnalise()).isEqualTo("REVALIDADO");
        assertThat(resultado.quarantine().getAnalisadoEm()).isNotNull();
        assertThat(resultado.quarantine().getDatasetVersaoId()).isEqualTo(versaoId);
        assertThat(jdbc.queryForMap(
                "SELECT estado, dataset_versao_id FROM imovel_rural WHERE cod_imovel = 'PR-TESTE-Q-001'"))
                .containsEntry("ESTADO", "PR")
                .hasEntrySatisfying("DATASET_VERSAO_ID", v -> assertThat(((Number) v).longValue()).isEqualTo(versaoId));
    }

    /** Aprovado na validação, mas a carga não criou versão: não há como gravar, e o registro não fecha. */
    @Test
    void revalidarAprovadoSemVersaoDeDadosRecusa() {
        mapearGeometria();
        long id = quarentenaComGeometria("{\"area_ha\": \"12.5\"}");
        service.corrigir(id, new CorrecaoRequestDTO("{\"cod_imovel\": \"PR-TESTE-Q-002\"}", null));
        repository.flush();

        assertThatThrownBy(() -> service.revalidar(id))
                .isInstanceOf(GravacaoNaoPermitidaException.class)
                .hasMessageStartingWith("A carga que trouxe o registro não criou versão de dados");
    }

    @Test
    void mensagemDoPacoteTiraOCodigoEAsLinhasDoOracle() {
        assertThat(QuarentenaService.mensagemDoPacote(
                "ORA-20014: Uma carga mais recente já atualizou.\nORA-06512: at \"GEO_RURAL.PKG_TRATADA\", line 230"))
                .isEqualTo("Uma carga mais recente já atualizou.");
    }

    // ----- corrigir -------------------------------------------------------------

    @Test
    void corrigirGuardaOPayload() {
        long id = quarentena("{\"area_ha\": \"12.5\"}");

        var registro = service.corrigir(id, new CorrecaoRequestDTO("{\"cod_imovel\": \"PR-001\"}", null));

        assertThat(registro.getStatusAnalise()).isEqualTo("CORRIGIDO");
        assertThat(registro.getPayloadCorrigido()).isEqualTo("{\"cod_imovel\": \"PR-001\"}");
    }

    /** A staging exige um objeto JSON: texto solto ou lista não servem. */
    @Test
    void corrigirComPayloadQueNaoEObjetoJsonFalha() {
        long id = quarentena("{\"area_ha\": \"12.5\"}");

        assertThatThrownBy(() -> service.corrigir(id, new CorrecaoRequestDTO("não é json", null)))
                .isInstanceOf(RequisicaoInvalidaException.class);
        assertThatThrownBy(() -> service.corrigir(id, new CorrecaoRequestDTO("[1, 2]", null)))
                .isInstanceOf(RequisicaoInvalidaException.class);
    }

    // ----- status ---------------------------------------------------------------

    @Test
    void registroDescartadoNaoAceitaMaisNada() {
        long id = quarentena("{\"area_ha\": \"12.5\"}");
        service.descartar(id, new DescarteRequestDTO("Registro de teste."));

        assertThatThrownBy(() -> service.revalidar(id)).isInstanceOf(QuarantineClosedException.class);
        assertThatThrownBy(() -> service.corrigir(id, new CorrecaoRequestDTO("{}", null)))
                .isInstanceOf(QuarantineClosedException.class);
        assertThatThrownBy(() -> service.iniciarAnalise(id)).isInstanceOf(QuarantineClosedException.class);
        assertThatThrownBy(() -> service.descartar(id, new DescarteRequestDTO("De novo.")))
                .isInstanceOf(QuarantineClosedException.class);
    }

    // ----- listagem e geometria -------------------------------------------------

    @Test
    void listarFiltraPelaFonte() {
        long id = quarentena("{\"area_ha\": \"12.5\"}");

        assertThat(service.listar(new QuarentenaFiltroDTO(null, fonteId, null, null), Pageable.unpaged()))
                .extracting(r -> r.getId()).containsExactly(id);
        assertThat(service.listar(new QuarentenaFiltroDTO(null, -1L, null, null), Pageable.unpaged()))
                .isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void geometriaVemEmGeoJson() {
        jdbc.update("""
                INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo, payload, geometria)
                VALUES (?, 1, 'teste da quarentena', '{}', SDO_GEOMETRY(2003, 4326, NULL,
                          SDO_ELEM_INFO_ARRAY(1, 1003, 1),
                          SDO_ORDINATE_ARRAY(-51.20, -23.35, -51.15, -23.35, -51.15, -23.30,
                                             -51.20, -23.30, -51.20, -23.35)))""", execucaoId);
        long comGeometria = ultimaQuarentena();
        long semGeometria = quarentena("{}");

        Map<String, Object> geoJson = service.buscarGeometria(comGeometria);

        assertThat(geoJson).containsEntry("type", "Polygon");
        assertThat(service.buscarGeometria(semGeometria)).isNull();
    }

    // ----- correção da geometria ------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void correcaoAutomaticaSugereGeometriaValidaSemGravar() {
        long id = quarentenaComGeometria("{}", BORBOLETA);

        Map<String, Object> sugestao = service.suggestGeometryFix(id);

        assertThat((String) sugestao.get("type")).isIn("Polygon", "MultiPolygon");
        assertThat(service.findCorrectedGeometry(id)).isNull();
        assertThat(repository.findById(id).orElseThrow().getStatusAnalise()).isEqualTo("PENDENTE");
    }

    @Test
    void correcaoAutomaticaRecusaGeometriaValidaOuAusente() {
        long valida = quarentenaComGeometria("{}");
        long semGeometria = quarentena("{}");

        assertThatThrownBy(() -> service.suggestGeometryFix(valida))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("já é válida");
        assertThatThrownBy(() -> service.suggestGeometryFix(semGeometria))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("desenhe uma nova");
    }

    @Test
    void salvarGeometriaCorrigidaDeixaCorrigidoEPreservaAOriginal() {
        long id = quarentenaComGeometria("{}", BORBOLETA);

        var registro = service.saveCorrectedGeometry(id, new GeometryCorrectionRequest(QUADRADO, "Vértice trocado."));

        assertThat(registro.getStatusAnalise()).isEqualTo("CORRIGIDO");
        assertThat(registro.getJustificativa()).isEqualTo("Vértice trocado.");
        assertThat(service.findCorrectedGeometry(id)).containsEntry("type", "Polygon");
        assertThat(service.buscarGeometria(id)).isNotNull();
    }

    @Test
    void salvarGeometriaQueAindaSeCruzaFalha() {
        long id = quarentenaComGeometria("{}", BORBOLETA);
        Map<String, Object> borboleta = Map.of("type", "Polygon", "coordinates", List.of(List.of(
                List.of(-49.25, -25.42), List.of(-49.20, -25.42), List.of(-49.20, -25.48),
                List.of(-49.26, -25.40), List.of(-49.25, -25.42))));

        assertThatThrownBy(() -> service.saveCorrectedGeometry(id, new GeometryCorrectionRequest(borboleta, null)))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("se cruza");
        assertThatThrownBy(() -> service.saveCorrectedGeometry(id,
                new GeometryCorrectionRequest(Map.of("type", "Polygon"), null)))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("GeoJSON");
    }

    /** O revalidar e o pkg_tratada usam a geometria corrigida no lugar da original. */
    @Test
    void revalidarUsaAGeometriaCorrigida() {
        mapearGeometria();
        jdbc.update("""
                INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona)
                VALUES ('teste_quarentena', 1, ?, 'TRATADA')""", execucaoId);
        long id = quarentenaComGeometria("{\"cod_imovel\": \"PR-TESTE-Q-GEO\"}", BORBOLETA);

        assertThat(service.revalidar(id).errors()).extracting(RevalidationError::errorType)
                .containsExactly("GEOMETRIA_INVALIDA");

        service.saveCorrectedGeometry(id, new GeometryCorrectionRequest(QUADRADO, null));
        repository.flush();
        RevalidationResponse resultado = service.revalidar(id);

        assertThat(resultado.status()).isEqualTo("APROVADO");
        assertThat(resultado.quarantine().getStatusAnalise()).isEqualTo("REVALIDADO");
        assertThat(jdbc.queryForObject("""
                SELECT pkg_quality.validate_geometry(geometria) FROM imovel_rural
                 WHERE cod_imovel = 'PR-TESTE-Q-GEO'""", String.class)).isEqualTo("TRUE");
    }

    @Test
    void validacaoDoOracleViraTextoParaATela() {
        assertThat(QuarentenaService.describeOracleValidation("13349 [Element <1>] [Ring <1>]"))
                .startsWith("o contorno do polígono se cruza");
        assertThat(QuarentenaService.describeOracleValidation("13999"))
                .isEqualTo("erro de geometria do Oracle (13999).");
    }

    // ----- montagem -------------------------------------------------------------

    /** Polígono em "laço" (as arestas se cruzam): GEOMETRIA_INVALIDA, código 13349. */
    private static final String BORBOLETA = """
            SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 1),
              SDO_ORDINATE_ARRAY(-49.25, -25.42, -49.20, -25.42, -49.20, -25.48, -49.26, -25.40, -49.25, -25.42))""";

    private static final Map<String, Object> QUADRADO = Map.of("type", "Polygon", "coordinates", List.of(List.of(
            List.of(-51.20, -23.35), List.of(-51.15, -23.35), List.of(-51.15, -23.30),
            List.of(-51.20, -23.30), List.of(-51.20, -23.35))));

    private long quarentena(String payload) {
        jdbc.update("""
                INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo, payload)
                VALUES (?, 1, 'teste da quarentena', ?)""", execucaoId, payload);
        return ultimaQuarentena();
    }

    /** O imovel_rural exige geometria: sem ela no mapeamento, nada grava na Tratada. */
    private void mapearGeometria() {
        jdbc.update("INSERT INTO fonte_coluna (fonte_id, nome_coluna) VALUES (?, 'geometry')", fonteId);
        jdbc.update("""
                INSERT INTO regra_campo
                  (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado, obrigatorio,
                   politica_geometria, recortar_pr)
                SELECT ?, id, 'imovel_rural', 'geometria', 'GEOMETRIA', 1, 'QUARENTENA', 0
                  FROM fonte_coluna WHERE fonte_id = ? AND nome_coluna = 'geometry'""", fonteId, fonteId);
    }

    private long quarentenaComGeometria(String payload) {
        return quarentenaComGeometria(payload, """
                SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 1),
                  SDO_ORDINATE_ARRAY(-51.20, -23.35, -51.15, -23.35, -51.15, -23.30,
                                     -51.20, -23.30, -51.20, -23.35))""");
    }

    private long quarentenaComGeometria(String payload, String geometriaSql) {
        jdbc.update("""
                INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo, payload, geometria)
                VALUES (?, 1, 'teste da quarentena', ?, %s)""".formatted(geometriaSql), execucaoId, payload);
        return ultimaQuarentena();
    }

    private long ultimaQuarentena() {
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM registro_quarentena WHERE execucao_id = ?", Long.class, execucaoId);
    }
}
