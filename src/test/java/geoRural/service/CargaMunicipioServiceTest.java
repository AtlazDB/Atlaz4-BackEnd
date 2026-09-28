package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.dto.CargaResponse;
import geoRural.entity.ArquivoBruto;
import geoRural.entity.DatasetVersao;
import geoRural.entity.Execucao;
import geoRural.entity.Fonte;
import geoRural.entity.Municipio;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.ArquivoBrutoRepository;
import geoRural.repository.DatasetVersaoRepository;
import geoRural.repository.FonteRepository;
import geoRural.repository.MunicipioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Roda contra o Oracle do docker-compose e SEM @Transactional de propósito: a
 * carga usa três transações próprias, e o caminho de erro só pode ser conferido
 * se a transação que grava o FALHOU realmente commitar. Por isso a limpeza é
 * manual, no @AfterEach.
 */
@SpringBootTest
class CargaMunicipioServiceTest {

    private static final String COD_A = "9999001";
    private static final String COD_B = "9999002";
    private static final String COD_C = "9999003";
    private static final List<String> CODIGOS_DE_TESTE = List.of(COD_A, COD_B, COD_C);

    /** Evita exigir ~/.oci/config só para subir o contexto do teste. */
    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private CargaMunicipioService cargaMunicipioService;

    @Autowired
    private ArquivoBrutoRepository arquivoRepository;

    @Autowired
    private FonteRepository fonteRepository;

    @Autowired
    private MunicipioRepository municipioRepository;

    @Autowired
    private DatasetVersaoRepository datasetVersaoRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> arquivosCriados = new ArrayList<>();

    @BeforeEach
    void limparResiduo() {
        apagarMunicipiosDeTeste();
    }

    @AfterEach
    void limpar() {
        apagarMunicipiosDeTeste();
        for (Long arquivoId : arquivosCriados) {
            jdbc.update("""
                    DELETE FROM dataset_versao
                     WHERE execucao_id IN (SELECT id FROM execucao WHERE arquivo_bruto_id = ?)
                    """, arquivoId);
            jdbc.update("DELETE FROM execucao WHERE arquivo_bruto_id = ?", arquivoId);
            jdbc.update("DELETE FROM arquivo_bruto WHERE id = ?", arquivoId);
        }
        arquivosCriados.clear();
    }

    @Test
    void carregaDoisMunicipiosValidos() {
        Long arquivoId = criarArquivoRecebido();
        int versaoAntes = datasetVersaoRepository.ultimaVersao(DatasetVersao.CONJUNTO_MUNICIPIOS);

        CargaResponse resposta = cargaMunicipioService.carregar(arquivoId, geoJsonComDoisMunicipios());

        assertThat(resposta.status()).isEqualTo(Execucao.STATUS_CONCLUIDA);
        assertThat(resposta.conjunto()).isEqualTo(DatasetVersao.CONJUNTO_MUNICIPIOS);
        assertThat(resposta.versao()).isEqualTo(versaoAntes + 1);
        assertThat(resposta.registrosLidos()).isEqualTo(2);
        assertThat(resposta.inseridos()).isEqualTo(2);
        assertThat(resposta.atualizados()).isZero();

        Municipio a = municipioRepository.findByCodIbge(COD_A).orElseThrow();
        assertThat(a.getNome()).isEqualTo("Municipio A");
        assertThat(a.getEstado()).isEqualTo("PR");
        assertThat(a.getRegiao()).isEqualTo("Sul");
        assertThat(a.getAreaKm2()).isEqualByComparingTo("150.5");
        assertThat(a.getGeometria().getSRID()).isEqualTo(4326);

        // Veio pelos nomes do IBGE (CD_MUN, NM_MUN, SIGLA_UF, AREA_KM2).
        Municipio b = municipioRepository.findByCodIbge(COD_B).orElseThrow();
        assertThat(b.getNome()).isEqualTo("Municipio B");
        assertThat(b.getEstado()).isEqualTo("SP");
        assertThat(b.getRegiao()).isEqualTo("Sudeste");

        assertThat(statusDoArquivo(arquivoId)).isEqualTo(ArquivoBruto.STATUS_PROCESSADO);
    }

    @Test
    void rejeitaCargaComCodigoIbgeInvalidoEGravaOErro() {
        Long arquivoId = criarArquivoRecebido();

        assertThatThrownBy(() -> cargaMunicipioService.carregar(arquivoId, geoJsonComCodigoInvalido()))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("7 dígitos");

        Map<String, Object> execucao = execucaoDoArquivo(arquivoId);
        assertThat(execucao.get("STATUS")).isEqualTo(Execucao.STATUS_FALHOU);
        assertThat((String) execucao.get("MENSAGEM_ERRO")).contains("123");
        assertThat(execucao.get("FINALIZADA_EM")).isNotNull();

        assertThat(statusDoArquivo(arquivoId)).isEqualTo(ArquivoBruto.STATUS_REJEITADO);

        // A transação da carga foi desfeita: nada de município nem de versão nova.
        assertThat(municipioRepository.findByCodIbge(COD_C)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_versao WHERE execucao_id = ?",
                Integer.class, execucao.get("ID"))).isZero();
    }

    @Test
    void municipioQueJaExisteEAtualizadoEApontaParaAVersaoNova() {
        Long primeiro = criarArquivoRecebido();
        cargaMunicipioService.carregar(primeiro, geoJsonComUmMunicipio("Nome Antigo", "PR"));
        Municipio antes = municipioRepository.findByCodIbge(COD_C).orElseThrow();

        Long segundo = criarArquivoRecebido();
        CargaResponse resposta = cargaMunicipioService.carregar(segundo, geoJsonComUmMunicipio("Nome Novo", "SP"));

        assertThat(resposta.inseridos()).isZero();
        assertThat(resposta.atualizados()).isEqualTo(1);

        Municipio depois = municipioRepository.findByCodIbge(COD_C).orElseThrow();
        assertThat(depois.getId()).isEqualTo(antes.getId());
        assertThat(depois.getNome()).isEqualTo("Nome Novo");
        assertThat(depois.getEstado()).isEqualTo("SP");
        assertThat(depois.getDatasetVersaoId()).isNotEqualTo(antes.getDatasetVersaoId());
    }

    // ---------------------------------------------------------------- apoio

    private Long criarArquivoRecebido() {
        Fonte ibge = fonteRepository.findAllByOrderByCriadaEmDesc().stream()
                .filter(f -> "IBGE".equals(f.getSigla()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A fonte IBGE da V1 não está no banco."));

        ArquivoBruto arquivo = new ArquivoBruto();
        arquivo.setFonte(ibge);
        arquivo.setChaveObjeto("bruta/IBGE/teste/" + UUID.randomUUID() + "_municipios.geojson");
        arquivo.setNomeOriginal("municipios-teste.geojson");
        arquivo.setTamanhoBytes(1024);
        arquivo.setContentType("application/geo+json");
        arquivo.setHashSha256("0".repeat(64));
        arquivo.setStatus(ArquivoBruto.STATUS_RECEBIDO);

        Long id = arquivoRepository.save(arquivo).getId();
        arquivosCriados.add(id);
        return id;
    }

    private String statusDoArquivo(Long arquivoId) {
        return jdbc.queryForObject("SELECT status FROM arquivo_bruto WHERE id = ?", String.class, arquivoId);
    }

    private Map<String, Object> execucaoDoArquivo(Long arquivoId) {
        return jdbc.queryForMap("""
                SELECT id, status, mensagem_erro, finalizada_em
                  FROM execucao
                 WHERE arquivo_bruto_id = ?
                """, arquivoId);
    }

    private void apagarMunicipiosDeTeste() {
        for (String codigo : CODIGOS_DE_TESTE) {
            jdbc.update("DELETE FROM municipio WHERE cod_ibge = ?", codigo);
        }
    }

    private String geoJsonComDoisMunicipios() {
        return """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {
                        "cod_ibge": "%s", "nome": "Municipio A",
                        "estado": "PR", "area_km2": 150.5
                      },
                      "geometry": %s
                    },
                    {
                      "type": "Feature",
                      "properties": {
                        "CD_MUN": "%s", "NM_MUN": "Municipio B",
                        "SIGLA_UF": "sp", "AREA_KM2": 90.25
                      },
                      "geometry": %s
                    }
                  ]
                }
                """.formatted(COD_A, retangulo(-49.30, -25.45, -49.05, -25.20),
                              COD_B, retangulo(-49.00, -25.45, -48.75, -25.20));
    }

    private String geoJsonComUmMunicipio(String nome, String uf) {
        return """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {
                        "cod_ibge": "%s", "nome": "%s",
                        "estado": "%s", "area_km2": 42.0
                      },
                      "geometry": %s
                    }
                  ]
                }
                """.formatted(COD_C, nome, uf, retangulo(-48.70, -25.45, -48.50, -25.20));
    }

    private String geoJsonComCodigoInvalido() {
        return """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {
                        "cod_ibge": "123", "nome": "Codigo Curto",
                        "estado": "PR", "area_km2": 10.0
                      },
                      "geometry": %s
                    }
                  ]
                }
                """.formatted(retangulo(-48.40, -25.45, -48.20, -25.20));
    }

    private String retangulo(double oeste, double sul, double leste, double norte) {
        return """
                {"type":"Polygon","coordinates":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}
                """.formatted(oeste, sul, leste, sul, leste, norte, oeste, norte, oeste, sul);
    }
}
