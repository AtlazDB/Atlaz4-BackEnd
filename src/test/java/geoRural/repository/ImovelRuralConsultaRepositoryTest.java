package geoRural.repository;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.dto.FiltroImoveis;
import geoRural.dto.ImovelResumoResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filtros da tabela e do mapa, contra o Oracle do docker-compose.
 *
 * Os imóveis de teste ficam em municípios fictícios e no meio do oceano (lon -35, lat -35), para
 * não se misturar com o CAR que cada um tenha carregado. Sem @Transactional de propósito: o índice
 * espacial só enxerga o que foi commitado (ver armadilhas no CLAUDE.md). Limpeza manual.
 */
@SpringBootTest
class ImovelRuralConsultaRepositoryTest {

    private static final String PREFIXO = "TESTE-FILTRO-";

    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private ImovelRuralConsultaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String CHAVE = "bruta/CAR/teste/filtro-imoveis.zip";

    private long datasetVersaoId;

    @BeforeEach
    void criar() {
        apagar();

        // Cadeia própria arquivo → execução → versão: não depende de nenhum dado de teste do banco.
        Long fonte = jdbc.queryForObject("SELECT id FROM fonte WHERE sigla = 'CAR'", Long.class);
        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256, status)
                VALUES (?, ?, 'filtro-imoveis.zip', 1, 'x', 'PROCESSADO')
                """, fonte, CHAVE);
        Long arquivo = jdbc.queryForObject("SELECT id FROM arquivo_bruto WHERE chave_objeto = ?", Long.class, CHAVE);
        jdbc.update("INSERT INTO execucao (arquivo_bruto_id, tipo, status) VALUES (?, 'VALIDACAO', 'CONCLUIDA')", arquivo);
        Long execucao = jdbc.queryForObject("SELECT id FROM execucao WHERE arquivo_bruto_id = ?", Long.class, arquivo);
        jdbc.update("INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona) VALUES ('teste_filtro', 1, ?, 'TRATADA')",
                execucao);
        datasetVersaoId = jdbc.queryForObject("SELECT id FROM dataset_versao WHERE execucao_id = ?", Long.class, execucao);

        // Mesmo esquema do caso real: "Ivai" contra "Ivaipora" e "Sao Joao do Ivai".
        inserir(PREFIXO + "1", "Testelandia", "AT", 0);
        inserir(PREFIXO + "2", "Testelandia", "CA", 1);
        inserir(PREFIXO + "3", "Testelandiapora", "AT", 2);
        inserir(PREFIXO + "4", "Sao Joao do Testelandia", "AT", 3);
    }

    @AfterEach
    void apagar() {
        jdbc.update("DELETE FROM imovel_rural WHERE cod_imovel LIKE ?", PREFIXO + "%");
        jdbc.update("""
                DELETE FROM dataset_versao WHERE execucao_id IN (SELECT e.id FROM execucao e
                  JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id WHERE a.chave_objeto = ?)
                """, CHAVE);
        jdbc.update("""
                DELETE FROM execucao WHERE arquivo_bruto_id IN (SELECT id FROM arquivo_bruto WHERE chave_objeto = ?)
                """, CHAVE);
        jdbc.update("DELETE FROM arquivo_bruto WHERE chave_objeto = ?", CHAVE);
    }

    @Test
    void municipioEhIgualdadeENaoContem() {
        assertThat(codigos(new FiltroImoveis("Testelandia", PREFIXO, null)))
                .containsExactly(PREFIXO + "1", PREFIXO + "2");
    }

    @Test
    void municipioIgnoraAcentoEMaiusculas() {
        // O CAR grava sem acento; o nome do IBGE vem com acento.
        assertThat(codigos(new FiltroImoveis("TESTELÂNDIA", PREFIXO, null)))
                .containsExactly(PREFIXO + "1", PREFIXO + "2");
    }

    @Test
    void filtraPorSituacao() {
        assertThat(codigos(new FiltroImoveis(null, PREFIXO, "ca")))
                .containsExactly(PREFIXO + "2");
        assertThat(repository.contar(new FiltroImoveis("Testelandia", PREFIXO, "AT"))).isEqualTo(1);
    }

    @Test
    void mapaAplicaMunicipioESituacao() {
        List<String> doMunicipio = repository.buscarNaArea(-36, -36, -34, -34,
                        new FiltroImoveis("Testelândia", null, null), 1000).stream()
                .map(ImovelRuralConsultaRepository.ImovelNoMapa::codImovel)
                .filter(c -> c.startsWith(PREFIXO)).sorted().toList();
        assertThat(doMunicipio).containsExactly(PREFIXO + "1", PREFIXO + "2");

        List<String> ativos = repository.buscarNaArea(-36, -36, -34, -34,
                        new FiltroImoveis("Testelandia", null, "AT"), 1000).stream()
                .map(ImovelRuralConsultaRepository.ImovelNoMapa::codImovel)
                .filter(c -> c.startsWith(PREFIXO)).toList();
        assertThat(ativos).containsExactly(PREFIXO + "1");
    }

    private List<String> codigos(FiltroImoveis filtro) {
        return repository.buscarPagina(filtro, 1, 50).stream().map(ImovelResumoResponse::codImovel).toList();
    }

    /** Quadradinho de 0,01° no oceano, anel anti-horário. */
    private void inserir(String cod, String municipio, String situacao, int deslocamento) {
        double lon = -35 + deslocamento * 0.02;
        double lat = -35;
        jdbc.update("""
                INSERT INTO imovel_rural (cod_imovel, municipio, estado, situacao, geometria, dataset_versao_id)
                VALUES (?, ?, 'PR', ?,
                        SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 1),
                                     SDO_ORDINATE_ARRAY(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)),
                        ?)
                """, cod, municipio, situacao,
                lon, lat, lon + 0.01, lat, lon + 0.01, lat + 0.01, lon, lat + 0.01, lon, lat,
                datasetVersaoId);
    }
}
