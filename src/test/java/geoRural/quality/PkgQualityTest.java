package geoRural.quality;

// Contra o Oracle do docker-compose. Precisa da V9 e da V11 aplicadas à mão (o Flyway fica
// desligado: aplicaria a V11 antes da V8–V10). Cada teste é desfeito no fim (@DataJpaTest),
// por isso o pkg_quality.validate não pode dar COMMIT.
// Os tipos de erro são os 13 códigos da V10 (tabela tipo_erro).

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Types;
import java.util.ArrayList;
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
        coluna("data_cadastro");
        coluna("ativo");

        regra("cod_imovel",    "TEXTO",    1, 1, null);
        regra("area_ha",       "DECIMAL",  0, 0, null);
        regra("situacao",      "TEXTO",    0, 0, "TESTE_SITUACAO");
        regra("data_cadastro", "DATA",     0, 0, null);
        regra("ativo",         "BOOLEANO", 0, 0, null);

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
        assertThat(erros(CAMADA, 1)).containsExactly("CAMPO_OBRIGATORIO_AUSENTE");
    }

    /** null no JSON conta como ausente, não como em branco. */
    @Test
    void campoObrigatorioNullNoJsonEAusente() {
        linha(CAMADA, 1, """
                {"cod_imovel": null, "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(erros(CAMADA, 1)).containsExactly("CAMPO_OBRIGATORIO_AUSENTE");
    }

    @Test
    void campoObrigatorioSoComEspacosEstaEmBranco() {
        linha(CAMADA, 1, """
                {"cod_imovel": "   ", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("CAMPO_EM_BRANCO");
    }

    /** Falso nulo: o arquivo traz um texto no lugar do vazio (NULL, N/A, -). */
    @Test
    void campoObrigatorioComFalsoNuloEstaEmBranco() {
        linha(CAMADA, 1, """
                {"cod_imovel": "N/A", "area_ha": "12.5", "situacao": "ATIVO"}""");
        linha(CAMADA, 2, """
                {"cod_imovel": "-", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(erros(CAMADA, 1)).containsExactly("CAMPO_EM_BRANCO");
        assertThat(erros(CAMADA, 2)).containsExactly("CAMPO_EM_BRANCO");
    }

    @Test
    void areaNaoNumericaReprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "abc", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("TIPO_DE_DADO_INVALIDO");
    }

    /** O DECIMAL usa ponto, como no CAR (combinado com o Rocha): vírgula reprova. */
    @Test
    void areaComVirgulaReprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12,5", "situacao": "ATIVO"}""");

        validar();

        assertThat(erros(CAMADA, 1)).containsExactly("TIPO_DE_DADO_INVALIDO");
    }

    /** Sem formato_origem, a DATA é DD/MM/YYYY (combinado com o Rocha). */
    @Test
    void dataNoFormatoDdMmYyyyAprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO", "data_cadastro": "21/10/2026"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
    }

    @Test
    void dataEmOutroFormatoReprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO", "data_cadastro": "2026-10-21"}""");
        linha(CAMADA, 2, """
                {"cod_imovel": "PR-002", "area_ha": "12.5", "situacao": "ATIVO", "data_cadastro": "31/02/2026"}""");

        validar();

        assertThat(erros(CAMADA, 1)).containsExactly("TIPO_DE_DADO_INVALIDO");
        assertThat(erros(CAMADA, 2)).containsExactly("TIPO_DE_DADO_INVALIDO");
    }

    /** O BOOLEANO aceita só SIM/NÃO (combinado com o Rocha), em qualquer caixa. */
    @Test
    void booleanoForaDeSimNaoReprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO", "ativo": "sim"}""");
        linha(CAMADA, 2, """
                {"cod_imovel": "PR-002", "area_ha": "12.5", "situacao": "ATIVO", "ativo": "Não"}""");
        linha(CAMADA, 3, """
                {"cod_imovel": "PR-003", "area_ha": "12.5", "situacao": "ATIVO", "ativo": "S"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
        assertThat(status(CAMADA, 2)).isEqualTo("APROVADO");
        assertThat(erros(CAMADA, 3)).containsExactly("TIPO_DE_DADO_INVALIDO");
    }

    @Test
    void situacaoForaDoDominioReprova() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "XPTO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("VALOR_FORA_DO_DOMINIO");
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
        assertThat(erros(CAMADA, 1)).containsExactly("CHAVE_NATURAL_DUPLICADA");
        assertThat(erros(CAMADA, 2)).containsExactly("CHAVE_NATURAL_DUPLICADA");
    }

    /** Uma linha com mais de um erro guarda todos (combinado com o Leandro). */
    @Test
    void linhaComDoisErrosGuardaOsDois() {
        linha(CAMADA, 1, """
                {"area_ha": "abc", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactlyInAnyOrder("CAMPO_OBRIGATORIO_AUSENTE", "TIPO_DE_DADO_INVALIDO");
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
        regraGeometriaComRecortePr();
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

    /** Sem recortar_pr a malha do PR não é usada, então não precisa estar carregada. */
    @Test
    void semRecortePrNaoPrecisaDeMunicipio() {
        regraGeometria();
        semMunicipioDoPr();
        linhaComPoligono(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
    }

    @Test
    void comMunicipioDoPrValidaNormalmente() {
        regraGeometriaComRecortePr();
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
        assertThat(erros(CAMADA, 1)).containsExactly("GEOMETRIA_MALFORMADA");
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
        assertThat(erros(CAMADA, 1)).containsExactly("GEOMETRIA_NULA");
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
        assertThat(erros(CAMADA, 1)).containsExactly("GEOMETRIA_INVALIDA");
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

    // O "PR" do banco local é o retângulo de Londrina da V5:
    // longitude -51.30 a -51.05, latitude -23.45 a -23.20.

    @Test
    void dentroDoPrAprova() {
        regraGeometriaComRecortePr();
        linhaComPoligono(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
        assertThat(erros(CAMADA, 1)).isEmpty();
    }

    /** Atravessa a borda leste de Londrina: aprova como veio, sem recorte. */
    @Test
    void parcialmenteForaDoPrAprova() {
        regraGeometriaComRecortePr();
        linhaComRetangulo(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""",
                -51.10, -23.35, -51.00, -23.30);

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("APROVADO");
        assertThat(erros(CAMADA, 1)).isEmpty();
    }

    /** Em São Paulo, sem encostar no PR: reprova e vai para a quarentena. */
    @Test
    void totalmenteForaDoPrReprova() {
        regraGeometriaComRecortePr();
        linhaComRetangulo(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""",
                -46.70, -23.60, -46.65, -23.55);

        validar();

        assertThat(status(CAMADA, 1)).isEqualTo("REPROVADO");
        assertThat(erros(CAMADA, 1)).containsExactly("FORA_DO_RECORTE_TERRITORIAL");
    }

    // ----- revalidar_registro (T6) ----------------------------------------------

    /** O operador corrigiu o payload: a revalidação usa o corrigido. */
    @Test
    void revalidarComPayloadCorrigidoAprova() {
        long id = quarentenaCorrigida("""
                {"area_ha": "12.5", "situacao": "ATIVO"}""", """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        JsonNode resultado = revalidar(id);

        assertThat(resultado.get("status").asText()).isEqualTo("APROVADO");
        assertThat(tiposDeErro(resultado)).isEmpty();
    }

    /** Sem correção vale o payload original, então o erro continua. */
    @Test
    void revalidarSemCorrecaoContinuaReprovado() {
        long id = quarentena("""
                {"area_ha": "12.5", "situacao": "ATIVO"}""");

        JsonNode resultado = revalidar(id);

        assertThat(resultado.get("status").asText()).isEqualTo("REPROVADO");
        assertThat(tiposDeErro(resultado)).containsExactly("CAMPO_OBRIGATORIO_AUSENTE");
        assertThat(resultado.get("erros").get(0).get("campo").asText()).isEqualTo("cod_imovel");
    }

    /** Só devolve o resultado: a staging fica como estava, e a sobra da DAG não conta como chave duplicada. */
    @Test
    void revalidarNaoGravaNada() {
        linha(CAMADA, 1, """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");
        long id = quarentenaCorrigida("""
                {"area_ha": "12.5", "situacao": "ATIVO"}""", """
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");

        JsonNode resultado = revalidar(id);

        assertThat(resultado.get("status").asText()).isEqualTo("APROVADO");
        assertThat(linhasNaStaging()).isEqualTo(1);
        assertThat(status(CAMADA, 1)).isEqualTo("PENDENTE");
        assertThat(erros(CAMADA, 1)).isEmpty();
    }

    @Test
    void revalidarIdInexistenteFalha() {
        assertThatThrownBy(() -> revalidar(-1))
                .hasMessageContaining("ORA-20002");
    }

    /** A geometria vem da quarentena (SDO_GEOMETRY) e volta a ser WKB para o validate. */
    @Test
    void revalidarUsaAGeometriaDaQuarentena() {
        regraGeometria(1);
        long comGeometria = quarentenaComPoligono("""
                {"cod_imovel": "PR-001", "area_ha": "12.5", "situacao": "ATIVO"}""");
        long semGeometria = quarentena("""
                {"cod_imovel": "PR-002", "area_ha": "8.0", "situacao": "ATIVO"}""");

        assertThat(revalidar(comGeometria).get("status").asText()).isEqualTo("APROVADO");
        assertThat(tiposDeErro(revalidar(semGeometria))).containsExactly("GEOMETRIA_NULA");
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

    /** Coluna de geometria com a política QUARENTENA, sem conferir o PR. */
    private void regraGeometria(int obrigatorio) {
        regraGeometria(obrigatorio, "QUARENTENA");
    }

    private void regraGeometria(int obrigatorio, String politica) {
        regraGeometria(obrigatorio, politica, 0);
    }

    /** Coluna de geometria opcional que confere se está no PR (recortar_pr = 1). */
    private void regraGeometriaComRecortePr() {
        regraGeometria(0, "QUARENTENA", 1);
    }

    /** Coluna de geometria (a V9 exige política quando o tipo é GEOMETRIA). */
    private void regraGeometria(int obrigatorio, String politica, int recortarPr) {
        coluna("geometria");
        jdbc.update("""
                INSERT INTO regra_campo
                  (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado,
                   obrigatorio, politica_geometria, recortar_pr)
                SELECT f.id, fc.id, 'imovel_rural', 'geometria', 'GEOMETRIA', ?, ?, ?
                  FROM fonte f
                  JOIN fonte_coluna fc ON fc.fonte_id = f.id AND fc.nome_coluna = 'geometria'
                 WHERE f.sigla = 'TESTE'""", obrigatorio, politica, recortarPr);
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

    /** Linha com um retângulo qualquer (x = longitude, y = latitude), sem SRID no WKB. */
    private void linhaComRetangulo(String camada, int numero, String atributosJson,
                                   double xMin, double yMin, double xMax, double yMax) {
        jdbc.update("""
                INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos, geometria_wkb)
                VALUES (?, ?, ?, ?, SDO_UTIL.TO_WKBGEOMETRY(SDO_GEOMETRY(2003, NULL, NULL,
                          SDO_ELEM_INFO_ARRAY(1, 1003, 1),
                          SDO_ORDINATE_ARRAY(?, ?, ?, ?, ?, ?, ?, ?, ?, ?))))""",
                execucaoId, camada, numero, atributosJson,
                xMin, yMin, xMax, yMin, xMax, yMax, xMin, yMax, xMin, yMin);
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

    /** Registro na quarentena sem correção do operador. */
    private long quarentena(String payload) {
        jdbc.update("""
                INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo, payload)
                VALUES (?, 1, 'teste do revalidar_registro', ?)""", execucaoId, payload);
        return ultimaQuarentena();
    }

    /** Registro na quarentena com o payload corrigido pelo operador. */
    private long quarentenaCorrigida(String payload, String payloadCorrigido) {
        jdbc.update("""
                INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo, payload, payload_corrigido)
                VALUES (?, 1, 'teste do revalidar_registro', ?, ?)""", execucaoId, payload, payloadCorrigido);
        return ultimaQuarentena();
    }

    /** Registro na quarentena com o quadrado de Londrina, já como SDO_GEOMETRY (como a V10 guarda). */
    private long quarentenaComPoligono(String payload) {
        jdbc.update("""
                INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo, payload, geometria)
                VALUES (?, 1, 'teste do revalidar_registro', ?, SDO_GEOMETRY(2003, 4326, NULL,
                          SDO_ELEM_INFO_ARRAY(1, 1003, 1),
                          SDO_ORDINATE_ARRAY(-51.20, -23.35, -51.15, -23.35, -51.15, -23.30,
                                             -51.20, -23.30, -51.20, -23.35)))""",
                execucaoId, payload);
        return ultimaQuarentena();
    }

    private long ultimaQuarentena() {
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM registro_quarentena WHERE execucao_id = ?", Long.class, execucaoId);
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

    /** Chama a função como o QuarentenaService chama e lê o JSON. */
    private JsonNode revalidar(long quarentenaId) {
        String json = jdbc.execute((ConnectionCallback<String>) con -> {
            try (CallableStatement cs = con.prepareCall("{? = call pkg_quality.revalidar_registro(?)}")) {
                cs.registerOutParameter(1, Types.CLOB);
                cs.setLong(2, quarentenaId);
                cs.execute();
                return cs.getString(1);
            }
        });
        try {
            return new ObjectMapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON inválido do revalidar_registro: " + json, e);
        }
    }

    private List<String> tiposDeErro(JsonNode resultado) {
        List<String> tipos = new ArrayList<>();
        resultado.get("erros").forEach(e -> tipos.add(e.get("tipo_erro").asText()));
        return tipos;
    }

    private int linhasNaStaging() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM staging_registro WHERE execucao_id = ?", Integer.class, execucaoId);
    }
}
