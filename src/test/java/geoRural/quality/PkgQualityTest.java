package geoRural.quality;

// Contra o Oracle do docker-compose. Precisa da V9 e da V11 aplicadas à mão (o Flyway fica
// desligado: aplicaria a V11 antes da V8–V10). Cada teste é desfeito no fim (@DataJpaTest),
// por isso o pkg_quality.validate não pode dar COMMIT.
// Os tipos de erro (OBRIGATORIO, TIPO...) são provisórios até a V10 trazer os 13 códigos.

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
class PkgQualityTest {

    private static final String ARQUIVO = "teste/pkg_quality/imoveis.zip";
    private static final String CAMADA = "imoveis.shp";

    @Autowired
    private JdbcTemplate jdbc;

    private long execucaoId;

    @BeforeEach
    void montarCenario() {
        jdbc.update("""
                INSERT INTO fonte (nome, sigla, orgao, formato)
                VALUES ('Fonte de teste do pkg_quality', 'TESTE', 'Teste', 'CSV')""");

        jdbc.update("INSERT INTO dominio (codigo, nome) VALUES ('TESTE_SITUACAO', 'Situação (teste)')");
        valorDominio("ATIVO");
        valorDominio("CANCELADO");

        coluna("cod_imovel");
        coluna("area_ha");
        coluna("situacao");

        regra("cod_imovel", "TEXTO",   1, 1, null);
        regra("area_ha",    "DECIMAL", 0, 0, null);
        regra("situacao",   "TEXTO",   0, 0, "TESTE_SITUACAO");

        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256)
                SELECT id, ?, 'imoveis.zip', 1, 'teste' FROM fonte WHERE sigla = 'TESTE'""", ARQUIVO);

        jdbc.update("""
                INSERT INTO execucao (arquivo_bruto_id, tipo)
                SELECT id, 'VALIDACAO' FROM arquivo_bruto WHERE chave_objeto = ?""", ARQUIVO);

        execucaoId = jdbc.queryForObject("""
                SELECT e.id FROM execucao e
                  JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id
                 WHERE a.chave_objeto = ?""", Long.class, ARQUIVO);
    }

    // ----- testes --------------------------------------------------------------

    @Test
    void linhaCompletaEAprovada() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
        assertThat(erros(CAMADA, 1)).isEmpty();
    }

    @Test
    void semCampoObrigatorioReprova() {
        linha(CAMADA, 1, """
                {"area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("OBRIGATORIO");
    }

    @Test
    void areaNaoNumericaReprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "abc", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("TIPO");
    }

    @Test
    void situacaoForaDoDominioReprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "XPTO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("DOMINIO");
    }

    /** Não dá para saber qual das duas está certa, então nenhuma entra. */
    @Test
    void chaveDuplicadaReprovaAsDuasLinhas() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");
        linha(CAMADA, 2, """
                {"cod_imovel": "PR-001", "area_ha": "30.0", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(status(CAMADA, 2)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("CHAVE_DUPLICADA");
        assertThat(erros(CAMADA, 2)).containsExactly("CHAVE_DUPLICADA");
    }

    /** Uma linha com mais de um erro guarda todos (combinado com o Leandro). */
    @Test
    void linhaComDoisErrosGuardaOsDois() {
        linha(CAMADA, 1, """
                {"area_ha": "abc", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactlyInAnyOrder("OBRIGATORIO", "TIPO");
    }

    /** O numero_linha recomeça em cada camada do .zip. */
    @Test
    void mesmaLinhaEmOutraCamadaEAprovada() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");
        linha("outra.shp", 1, """
                {"cod_imovel": "PR-002", "area_ha": "8.0", "situacao": "CANCELADO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
        assertThat(status("outra.shp", 1)).isEqualTo("APROVADO");
    }

    /** A DAG e a quarentena acham o registro pela chave natural, então ela vai também nas reprovadas. */
    @Test
    void preencheChaveNaturalInclusiveNaLinhaReprovada() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");
        linha(CAMADA, 2, """
                {"cod_imovel": "PR-002", "area_ha": "8.0", "situacao": "XPTO"}""");

        validar();

        assertThat(status(CAMADA, 2)).isEqualTo("REPROVADO");
        assertThat(chaveNatural(CAMADA, 1)).isEqualTo("PR-001");
        assertThat(chaveNatural(CAMADA, 2)).isEqualTo("PR-002");
    }

    // ----- montagem do cenário ------------------------------------------------

    private void valorDominio(String valor) {
        jdbc.update("""
                INSERT INTO dominio_valor (dominio_id, valor)
                SELECT id, ? FROM dominio WHERE codigo = 'TESTE_SITUACAO'""", valor);
    }

    private void coluna(String nome) {
        jdbc.update("""
                INSERT INTO fonte_coluna (fonte_id, nome_coluna)
                SELECT id, ? FROM fonte WHERE sigla = 'TESTE'""", nome);
    }

    /** Mapeia a coluna para imovel_rural com o mesmo nome. */
    private void regra(String coluna, String tipo, int obrigatorio, int chave, String dominio) {
        jdbc.update("""
                INSERT INTO regra_campo
                  (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado,
                   obrigatorio, chave, dominio_id)
                SELECT f.id, fc.id, 'imovel_rural', ?, ?, ?, ?,
                       (SELECT id FROM dominio WHERE codigo = ?)
                  FROM fonte f
                  JOIN fonte_coluna fc ON fc.fonte_id = f.id AND fc.nome_coluna = ?
                 WHERE f.sigla = 'TESTE'""",
                coluna, tipo, obrigatorio, chave, dominio, coluna);
    }

    private void linha(String camada, int numero, String atributosJson) {
        jdbc.update("""
                INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos)
                VALUES (?, ?, ?, ?)""", execucaoId, camada, numero, atributosJson);
    }

    // ----- execução e conferência -----------------------------------------------

    private void validar() {
        jdbc.update("BEGIN pkg_quality.validate(?); END;", execucaoId);
    }

    private String status(String camada, int numero) {
        return jdbc.queryForObject("""
                SELECT status FROM staging_registro
                 WHERE execucao_id = ? AND camada = ? AND numero_linha = ?""",
                String.class, execucaoId, camada, numero);
    }

    private String chaveNatural(String camada, int numero) {
        return jdbc.queryForObject("""
                SELECT chave_natural FROM staging_registro
                 WHERE execucao_id = ? AND camada = ? AND numero_linha = ?""",
                String.class, execucaoId, camada, numero);
    }

    private List<String> erros(String camada, int numero) {
        return jdbc.queryForList("""
                SELECT e.tipo_erro
                  FROM staging_erro e
                  JOIN staging_registro s ON s.id = e.staging_id
                 WHERE s.execucao_id = ? AND s.camada = ? AND s.numero_linha = ?""",
                String.class, execucaoId, camada, numero);
    }
}
