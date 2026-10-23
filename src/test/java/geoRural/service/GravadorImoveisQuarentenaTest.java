package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.dto.ImovelLido;
import geoRural.service.ValidadorImovel.Resultado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quarentena do processamento Java sai completa: tabela de destino, chave, tipo de erro,
 * versão e geometria, que é o que a tela e o "Revalidar" (pkg_tratada) usam.
 * Contra o Oracle do docker-compose; cada teste é desfeito no fim.
 */
@SpringBootTest
@Transactional
class GravadorImoveisQuarentenaTest {

    private static final String ARQUIVO = "teste/gravador/imoveis.zip";
    private static final GeometryFactory GF = new GeometryFactory();

    /** O cliente da OCI: evita exigir ~/.oci/config. */
    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private GravadorImoveis gravador;

    @Autowired
    private JdbcTemplate jdbc;

    private long execucaoId;
    private long versaoId;

    @BeforeEach
    void montarCenario() {
        long fonteId = jdbc.queryForObject("SELECT id FROM fonte WHERE sigla = 'CAR'", Long.class);
        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256)
                VALUES (?, ?, 'imoveis.zip', 1, 'teste')""", fonteId, ARQUIVO);
        long arquivoId = jdbc.queryForObject(
                "SELECT id FROM arquivo_bruto WHERE chave_objeto = ?", Long.class, ARQUIVO);
        execucaoId = gravador.iniciarExecucao(arquivoId);
        versaoId = gravador.criarVersao(execucaoId).id();
    }

    @Test
    void reprovadoVaiComChaveTipoVersaoEGeometria() {
        // Quadrado no sentido horário, como o shapefile guarda
        Geometry quadrado = poligono(-51.20, -23.35, -51.20, -23.30, -51.15, -23.30, -51.15, -23.35, -51.20, -23.35);
        ValidadorImovel validador = new ValidadorImovel();
        validador.validar(imovel(1, "PR-TESTE-GRAVADOR-1", quadrado));
        Resultado duplicado = validador.validar(imovel(2, "PR-TESTE-GRAVADOR-1", quadrado));
        Resultado semGeometria = validador.validar(imovel(3, "PR-TESTE-GRAVADOR-2", null));

        gravador.gravarQuarentena(execucaoId, versaoId, GravadorImoveis.ENTIDADE, List.of(duplicado, semGeometria));

        List<Map<String, Object>> linhas = jdbc.queryForList("""
                SELECT entidade, chave_natural, tipo_erro, valor_recebido, dataset_versao_id,
                       CASE WHEN geometria IS NULL THEN 'SEM'
                            ELSE SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, 0.05) END geometria
                  FROM registro_quarentena WHERE execucao_id = ? ORDER BY numero_linha""", execucaoId);

        assertThat(linhas).hasSize(2);
        assertThat(linhas.get(0))
                .containsEntry("ENTIDADE", "imovel_rural")
                .containsEntry("CHAVE_NATURAL", "PR-TESTE-GRAVADOR-1")
                .containsEntry("TIPO_ERRO", "CHAVE_NATURAL_DUPLICADA")
                .containsEntry("VALOR_RECEBIDO", "PR-TESTE-GRAVADOR-1")
                // válida e já no sentido do Oracle: o "Revalidar" consegue gravá-la
                .containsEntry("GEOMETRIA", "TRUE");
        assertThat(((Number) linhas.get(0).get("DATASET_VERSAO_ID")).longValue()).isEqualTo(versaoId);
        assertThat(linhas.get(1))
                .containsEntry("TIPO_ERRO", "GEOMETRIA_NULA")
                .containsEntry("GEOMETRIA", "SEM");
    }

    /** Reprocessar o mesmo arquivo: os abertos da execução anterior viram SUBSTITUIDO; o resto fica. */
    @Test
    void reprocessarOMesmoArquivoSubstituiOsAbertos() {
        long pendente = reprovadoDuplicado("PR-TESTE-SUBST-1");
        long corrigido = reprovadoDuplicado("PR-TESTE-SUBST-2");
        long descartadoPelaDag = reprovadoDuplicado("PR-TESTE-SUBST-5");
        long descartadoPeloOperador = reprovadoDuplicado("PR-TESTE-SUBST-6");
        jdbc.update("UPDATE registro_quarentena SET status_analise = 'CORRIGIDO' WHERE id = ?", corrigido);
        jdbc.update("""
                UPDATE registro_quarentena SET status_analise = 'DESCARTADO',
                       analisado_por = 'DAG processar_arquivo (desempate de código repetido)'
                 WHERE id = ?""", descartadoPelaDag);
        jdbc.update("UPDATE registro_quarentena SET status_analise = 'DESCARTADO' WHERE id = ?",
                descartadoPeloOperador);

        long arquivoId = jdbc.queryForObject(
                "SELECT arquivo_bruto_id FROM execucao WHERE id = ?", Long.class, execucaoId);
        long novaExecucao = gravador.iniciarExecucao(arquivoId);
        gravador.criarVersao(novaExecucao);

        assertThat(gravador.substituirPendentes(novaExecucao)).isEqualTo(2);
        assertThat(status(pendente)).isEqualTo("SUBSTITUIDO");
        // A carga nova decide de novo o que a DAG decidiu sozinha; o que uma pessoa fez, fica.
        assertThat(status(descartadoPelaDag)).isEqualTo("SUBSTITUIDO");
        assertThat(status(descartadoPeloOperador)).isEqualTo("DESCARTADO");
        assertThat(status(corrigido)).isEqualTo("CORRIGIDO");  // o operador já trabalhou nele
        assertThat(jdbc.queryForObject("SELECT justificativa FROM registro_quarentena WHERE id = ?",
                String.class, pendente)).startsWith("Substituído pela carga da execução " + novaExecucao);
    }

    /** Outro arquivo que grava o mesmo código encerra o registro antigo; os outros códigos ficam. */
    @Test
    void outroArquivoSoSubstituiOsCodigosQueTrouxe() {
        long mesmoCodigo = reprovadoDuplicado("PR-TESTE-SUBST-3");
        long outroCodigo = reprovadoDuplicado("PR-TESTE-SUBST-4");

        long fonteId = jdbc.queryForObject("SELECT id FROM fonte WHERE sigla = 'CAR'", Long.class);
        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256)
                VALUES (?, 'teste/gravador/municipio.zip', 'municipio.zip', 1, 'teste2')""", fonteId);
        long outroArquivo = jdbc.queryForObject(
                "SELECT id FROM arquivo_bruto WHERE chave_objeto = 'teste/gravador/municipio.zip'", Long.class);
        long novaExecucao = gravador.iniciarExecucao(outroArquivo);
        long novaVersao = gravador.criarVersao(novaExecucao).id();
        Resultado aprovado = new ValidadorImovel().validar(imovel(1, "PR-TESTE-SUBST-3", quadrado()));
        gravador.gravarImoveis(novaVersao, List.of(aprovado));

        assertThat(gravador.substituirPendentes(novaExecucao)).isEqualTo(1);
        assertThat(status(mesmoCodigo)).isEqualTo("SUBSTITUIDO");
        assertThat(status(outroCodigo)).isEqualTo("PENDENTE");
    }

    @Test
    void motivoViraOCodigoDaV10() {
        assertThat(GravadorImoveis.tipoErro("cod_imovel vazio")).isEqualTo("CAMPO_OBRIGATORIO_AUSENTE");
        assertThat(GravadorImoveis.tipoErro("cod_imovel duplicado")).isEqualTo("CHAVE_NATURAL_DUPLICADA");
        assertThat(GravadorImoveis.tipoErro("sem geometria")).isEqualTo("GEOMETRIA_NULA");
        assertThat(GravadorImoveis.tipoErro("geometria não é polígono: Point")).isEqualTo("GEOMETRIA_TIPO_INCOMPATIVEL");
        assertThat(GravadorImoveis.tipoErro("geometria inválida: Self-intersection")).isEqualTo("GEOMETRIA_INVALIDA");
        assertThat(GravadorImoveis.tipoErro("num_area negativo")).isNull();
    }

    /** Grava na quarentena da execução do cenário a segunda ocorrência de um código. Devolve o id. */
    private long reprovadoDuplicado(String cod) {
        ValidadorImovel validador = new ValidadorImovel();
        validador.validar(imovel(1, cod, quadrado()));
        gravador.gravarQuarentena(execucaoId, versaoId, GravadorImoveis.ENTIDADE,
                List.of(validador.validar(imovel(2, cod, quadrado()))));
        return jdbc.queryForObject("SELECT MAX(id) FROM registro_quarentena WHERE chave_natural = ?",
                Long.class, cod);
    }

    private String status(long quarentenaId) {
        return jdbc.queryForObject("SELECT status_analise FROM registro_quarentena WHERE id = ?",
                String.class, quarentenaId);
    }

    private static Geometry quadrado() {
        return poligono(-51.20, -23.35, -51.20, -23.30, -51.15, -23.30, -51.15, -23.35, -51.20, -23.35);
    }

    private static ImovelLido imovel(int linha, String cod, Geometry geometria) {
        return new ImovelLido(linha, cod, "Londrina", new BigDecimal("12.5"), "AT", geometria,
                Map.of("cod_imovel", cod, "municipio", "Londrina"));
    }

    private static Geometry poligono(double... xy) {
        Coordinate[] pontos = new Coordinate[xy.length / 2];
        for (int i = 0; i < pontos.length; i++) {
            pontos[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        }
        return GF.createPolygon(pontos);
    }
}
