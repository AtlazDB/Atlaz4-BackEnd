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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** Sem a malha do PR, todo imóvel cairia como fora do PR: a execução falha em vez disso. */
    @Test
    void semMunicipioDoPrFalhaAExecucao() {
        regraGeometria();
        semMunicipioDoPr();
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        assertThatThrownBy(this::validar)
                .hasMessageContaining("ORA-20001")
                .hasMessageContaining("malha do IBGE");
    }

    /** A trava só vale para fonte com coluna de geometria. */
    @Test
    void semRegraDeGeometriaNaoPrecisaDeMunicipio() {
        semMunicipioDoPr();
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
    }

    @Test
    void comMunicipioDoPrValidaNormalmente() {
        regraGeometria();
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
    }

    /** O WKB não traz SRID: o validate põe 4326 (a DAG já reprojeta). */
    @Test
    void wkbValidoViraGeometriaComSrid4326() {
        regraGeometria();
        linhaComPoligono(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
        assertThat(sridDaGeometria(CAMADA, 1)).isEqualTo(4326);
    }

    /** Um WKB quebrado reprova só a linha dele, sem derrubar a validação. */
    @Test
    void wkbIlegivelReprova() {
        regraGeometria();
        linhaComWkb(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""",
                "lixo".getBytes(StandardCharsets.UTF_8));
        linhaComPoligono(CAMADA, 2, """
                {"cod_imovel": "PR-002", "area_ha": "8.0", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("GEOMETRIA");
        assertThat(status(CAMADA, 2)).isEqualTo("APROVADO");
    }

    /** A geometria vem em geometria_wkb, não no JSON: o obrigatório dela olha lá. */
    @Test
    void geometriaObrigatoriaAusenteReprova() {
        regraGeometria(1);
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");
        linhaComPoligono(CAMADA, 2, """
                {"cod_imovel": "PR-002", "area_ha": "8.0", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("OBRIGATORIO");
        assertThat(status(CAMADA, 2)).isEqualTo("APROVADO");
    }

    /** Política QUARENTENA: geometria inválida reprova, sem tentar corrigir. */
    @Test
    void geometriaInvalidaComQuarentenaReprova() {
        regraGeometria(0, "QUARENTENA");
        linhaComPoligonoInvalido(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("GEOMETRIA");
        assertThat(correcoes()).isZero();
    }

    /** Política CORRIGIR_E_REGISTRAR: corrige, guarda o antes e o depois e aprova. */
    @Test
    void geometriaInvalidaComCorrecaoEAprovadaERegistrada() {
        regraGeometria(0, "CORRIGIR_E_REGISTRAR");
        linhaComPoligonoInvalido(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
        assertThat(erros(CAMADA, 1)).isEmpty();
        assertThat(correcoes()).isEqualTo(1);
        assertThat(chaveDaCorrecao()).isEqualTo("PR-001");
        assertThat(validadeDaGeometria(CAMADA, 1)).isEqualTo("TRUE");
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

    /** Coluna de geometria opcional. */
    private void regraGeometria() {
        regraGeometria(0);
    }

    /** Coluna de geometria com a política QUARENTENA. */
    private void regraGeometria(int obrigatorio) {
        regraGeometria(obrigatorio, "QUARENTENA");
    }

    /** Coluna de geometria (a V9 exige política quando o tipo é GEOMETRIA). */
    private void regraGeometria(int obrigatorio, String politica) {
        coluna("geometria");
        jdbc.update("""
                INSERT INTO regra_campo
                  (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado,
                   obrigatorio, politica_geometria)
                SELECT f.id, fc.id, 'imovel_rural', 'geometria', 'GEOMETRIA', ?, ?
                  FROM fonte f
                  JOIN fonte_coluna fc ON fc.fonte_id = f.id AND fc.nome_coluna = 'geometria'
                 WHERE f.sigla = 'TESTE'""", obrigatorio, politica);
    }

    /**
     * Tira o PR da municipio só dentro do teste (o rollback desfaz). UPDATE em vez de
     * DELETE porque o imóvel TESTE-001 da V5 aponta para Londrina.
     */
    private void semMunicipioDoPr() {
        jdbc.update("UPDATE municipio SET estado = 'SP' WHERE estado = 'PR'");
    }

    private void linha(String camada, int numero, String atributosJson) {
        jdbc.update("""
                INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos)
                VALUES (?, ?, ?, ?)""", execucaoId, camada, numero, atributosJson);
    }

    /**
     * Linha com um quadrado dentro do retângulo de Londrina (V5). O WKB é gerado pelo
     * próprio Oracle, sem SRID, como o que a DAG grava.
     */
    private void linhaComPoligono(String camada, int numero, String atributosJson) {
        jdbc.update("""
                INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos, geometria_wkb)
                VALUES (?, ?, ?, ?, SDO_UTIL.TO_WKBGEOMETRY(SDO_GEOMETRY(2003, NULL, NULL,
                          SDO_ELEM_INFO_ARRAY(1, 1003, 1),
                          SDO_ORDINATE_ARRAY(-51.20, -23.35, -51.15, -23.35, -51.15, -23.30,
                                             -51.20, -23.30, -51.20, -23.35))))""",
                execucaoId, camada, numero, atributosJson);
    }

    /** O mesmo quadrado, com um vértice repetido (erro 13356): inválido, mas corrigível. */
    private void linhaComPoligonoInvalido(String camada, int numero, String atributosJson) {
        jdbc.update("""
                INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos, geometria_wkb)
                VALUES (?, ?, ?, ?, SDO_UTIL.TO_WKBGEOMETRY(SDO_GEOMETRY(2003, NULL, NULL,
                          SDO_ELEM_INFO_ARRAY(1, 1003, 1),
                          SDO_ORDINATE_ARRAY(-51.20, -23.35, -51.15, -23.35, -51.15, -23.35,
                                             -51.15, -23.30, -51.20, -23.30, -51.20, -23.35))))""",
                execucaoId, camada, numero, atributosJson);
    }

    private void linhaComWkb(String camada, int numero, String atributosJson, byte[] wkb) {
        jdbc.update("""
                INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos, geometria_wkb)
                VALUES (?, ?, ?, ?, ?)""", execucaoId, camada, numero, atributosJson, wkb);
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

    private Integer sridDaGeometria(String camada, int numero) {
        return jdbc.queryForObject("""
                SELECT s.geometria.sdo_srid FROM staging_registro s
                 WHERE s.execucao_id = ? AND s.camada = ? AND s.numero_linha = ?""",
                Integer.class, execucaoId, camada, numero);
    }

    private String chaveDaCorrecao() {
        return jdbc.queryForObject(
                "SELECT chave_natural FROM correcao_automatica WHERE execucao_id = ?",
                String.class, execucaoId);
    }

    private int correcoes() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM correcao_automatica WHERE execucao_id = ?",
                Integer.class, execucaoId);
    }

    private String validadeDaGeometria(String camada, int numero) {
        return jdbc.queryForObject("""
                SELECT SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(s.geometria, 0.05)
                  FROM staging_registro s
                 WHERE s.execucao_id = ? AND s.camada = ? AND s.numero_linha = ?""",
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
