package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.dto.CorrecaoRequestDTO;
import geoRural.dto.DescarteRequestDTO;
import geoRural.dto.QuarentenaFiltroDTO;
import geoRural.dto.RevalidationError;
import geoRural.dto.RevalidationResponse;
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

    @Test
    void revalidarAprovadoMarcaRevalidado() {
        long id = quarentena("{\"area_ha\": \"12.5\"}");
        service.corrigir(id, new CorrecaoRequestDTO("{\"cod_imovel\": \"PR-001\"}", "Faltava o código."));
        // Na tela são duas requisições; aqui é uma transação só, e o pacote lê do banco.
        repository.flush();

        RevalidationResponse resultado = service.revalidar(id);

        assertThat(resultado.status()).isEqualTo("APROVADO");
        assertThat(resultado.errors()).isEmpty();
        assertThat(resultado.quarantine().getStatusAnalise()).isEqualTo("REVALIDADO");
        assertThat(resultado.quarantine().getAnalisadoEm()).isNotNull();
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

    // ----- montagem -------------------------------------------------------------

    private long quarentena(String payload) {
        jdbc.update("""
                INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo, payload)
                VALUES (?, 1, 'teste da quarentena', ?)""", execucaoId, payload);
        return ultimaQuarentena();
    }

    private long ultimaQuarentena() {
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM registro_quarentena WHERE execucao_id = ?", Long.class, execucaoId);
    }
}
