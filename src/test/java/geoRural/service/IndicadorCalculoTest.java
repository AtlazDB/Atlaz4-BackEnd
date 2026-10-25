package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.dto.IndicadorResponse;
import geoRural.dto.IndicadoresImovelResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O pkg_indicadores contra o Oracle, com um caso montado à mão e conferível na calculadora.
 * Roda numa transação desfeita no fim: o pacote não dá COMMIT, então nada fica no banco.
 *
 * Os imóveis ficam no oceano (-40, -30): o banco só guarda focos do Paraná, então nenhum foco
 * real pode cair dentro deles e mudar a conta.
 */
@SpringBootTest
@Transactional
class IndicadorCalculoTest {

    // Códigos no formato do CAR: a cobertura da RL é pelo código IBGE dentro do cod_imovel.
    // 9999901 e 9999902 não existem: nenhuma RL real cai nesses municípios.
    /** Imóvel com RL e focos: 100,5 ha */
    private static final String COM_RL = "PR-9999901-TESTEIND1";
    /** Mesmo município, nenhuma RL declarada */
    private static final String SEM_RL = "PR-9999901-TESTEIND2";
    /** Município que não veio em nenhum arquivo de RL */
    private static final String SEM_COBERTURA = "PR-9999902-TESTEIND3";
    /** RL declarada maior que o próprio imóvel (fragmentos sobrepostos) */
    private static final String RL_MAIOR = "PR-9999901-TESTEIND4";

    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private IndicadorService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void montarCaso() {
        Long execucao = jdbc.queryForObject("SELECT MIN(id) FROM execucao", Long.class);
        long versaoImovel = versao("imoveis_rurais", execucao);
        long versaoRl = versao("reserva_legal", execucao);
        long versaoFocos = versao("focos_calor", execucao);

        imovel(COM_RL, "Municipio Teste Indicadores", "100.5", -40.17, -30.31, -40.16, -30.30, versaoImovel);
        imovel(SEM_RL, "Municipio Teste Indicadores", "50", -40.15, -30.31, -40.14, -30.30, versaoImovel);
        imovel(SEM_COBERTURA, "Municipio Sem Cobertura", "80", -40.13, -30.31, -40.12, -30.30, versaoImovel);
        imovel(RL_MAIOR, "Municipio Teste Indicadores", "10", -40.11, -30.31, -40.10, -30.30, versaoImovel);

        // 12 + 3,5 = 15,5 ha de RL
        reservaLegal(COM_RL, "12", -40.169, -30.309, -40.165, -30.305, versaoRl);
        reservaLegal(COM_RL, "3.5", -40.164, -30.304, -40.162, -30.302, versaoRl);
        reservaLegal(RL_MAIOR, "8", -40.109, -30.309, -40.105, -30.305, versaoRl);
        reservaLegal(RL_MAIOR, "7", -40.106, -30.306, -40.102, -30.302, versaoRl);

        LocalDateTime agora = LocalDateTime.now(ZoneOffset.UTC);
        foco("t1", agora.minusMonths(1), "AQUA_M-T", -40.168, -30.306, versaoFocos);
        foco("t2", agora.minusMonths(7), "AQUA_M-T", -40.163, -30.303, versaoFocos);
        foco("t3", agora.minusMonths(13), "AQUA_M-T", -40.165, -30.305, versaoFocos); // fora da janela
        foco("t4", agora.minusMonths(1), "NOAA-20", -40.166, -30.306, versaoFocos);   // outro satélite
        foco("t5", agora.minusMonths(1), "AQUA_M-T", -40.110, -30.290, versaoFocos);  // fora de qualquer imóvel
    }

    @Test
    void irlBateComAContaFeitaAMao() {
        calcularCaso();

        IndicadorResponse irl = indicador(COM_RL, "IRL");
        // 15,5 / 100,5 x 100 = 15,4229% → abaixo de 20%: Crítico. Saldo: 15,5 - 20,1 = -4,6 ha
        assertThat(irl.valor()).isEqualByComparingTo("15.4229");
        assertThat(irl.status()).isEqualTo("CRITICO");
        assertThat(irl.numerador()).isEqualByComparingTo("15.5");
        assertThat(irl.areaImovelHa()).isEqualByComparingTo("100.5");
        assertThat(irl.saldoHa()).isEqualByComparingTo("-4.6");
        assertThat(irl.unidade()).isEqualTo("%");
        assertThat(irl.regra().codigo()).isEqualTo("RN-IRL-Min20pct");
        assertThat(irl.regra().versao()).isEqualTo(1);
        assertThat(irl.regra().parametros()).containsEntry("percentual_minimo", 20);
        assertThat(irl.versaoImovel().conjunto()).isEqualTo("imoveis_rurais");
        assertThat(irl.versaoInsumo().conjunto()).isEqualTo("reserva_legal");
        assertThat(irl.calculadoEm()).isNotNull();
    }

    @Test
    void ifcContaSoOSateliteDeReferenciaDentroDaJanelaEDoImovel() {
        calcularCaso();

        IndicadorResponse ifc = indicador(COM_RL, "IFC");
        // t1 e t2 contam; t3 (13 meses), t4 (NOAA-20) e t5 (fora do imóvel) não.
        // 2 / 100,5 x 1000 = 19,9005 → acima de zero e abaixo de 20: Alerta
        assertThat(ifc.numerador()).isEqualByComparingTo("2");
        assertThat(ifc.valor()).isEqualByComparingTo("19.9005");
        assertThat(ifc.status()).isEqualTo("ALERTA");
        assertThat(ifc.saldoHa()).isNull();
        assertThat(ifc.regra().codigo()).isEqualTo("RN-IFC-Queimadas");
        assertThat(ifc.versaoInsumo().conjunto()).isEqualTo("focos_calor");
    }

    @Test
    void imovelDeMunicipioCobertoSemRlFicaComZeroEFocoZeroEConforme() {
        calcularCaso();

        IndicadorResponse irl = indicador(SEM_RL, "IRL");
        assertThat(irl.valor()).isEqualByComparingTo("0");
        assertThat(irl.status()).isEqualTo("CRITICO");
        assertThat(irl.saldoHa()).isEqualByComparingTo("-10"); // 0 - 20% de 50 ha

        IndicadorResponse ifc = indicador(SEM_RL, "IFC");
        assertThat(ifc.numerador()).isEqualByComparingTo("0");
        assertThat(ifc.status()).isEqualTo("CONFORME");
    }

    @Test
    void imovelForaDaCoberturaDaRlNaoGanhaIrl() {
        calcularCaso();

        IndicadoresImovelResponse resposta = service.buscarPorImovel(SEM_COBERTURA);
        assertThat(resposta.indicadores()).extracting(IndicadorResponse::indicador)
                .containsExactly("IFC"); // IFC sim (focos cobrem o estado), IRL não
    }

    /** Como no DataGIS (V072): 8 + 7 = 15 ha de RL num imóvel de 10 ha contam como 10 ha (100%). */
    @Test
    void rlMaiorQueOImovelContaComoCemPorCento() {
        calcularCaso();

        IndicadorResponse irl = indicador(RL_MAIOR, "IRL");
        assertThat(irl.valor()).isEqualByComparingTo("100");
        assertThat(irl.numerador()).isEqualByComparingTo("10");
        assertThat(irl.status()).isEqualTo("CONFORME");
    }

    @Test
    void recalcularComAsMesmasVersoesNaoDuplica() {
        calcularCaso();
        calcularCaso();

        assertThat(service.historico(COM_RL, "IRL")).hasSize(1);
        assertThat(service.historico(COM_RL, "IFC")).hasSize(1);
    }

    /**
     * Calcula só os imóveis deste caso, pelas mesmas funções que o service.calcular() chama: com o PR
     * carregado no banco local, recalcular os 559 mil imóveis levaria ~15 min por teste.
     */
    private void calcularCaso() {
        for (String cod : List.of(COM_RL, SEM_RL, SEM_COBERTURA, RL_MAIOR)) {
            jdbc.execute((java.sql.Connection c) -> {
                for (String funcao : List.of("calcular_irl", "calcular_ifc")) {
                    try (var chamada = c.prepareCall("{? = call pkg_indicadores." + funcao + "(?)}")) {
                        chamada.registerOutParameter(1, java.sql.Types.NUMERIC);
                        chamada.setString(2, cod);
                        chamada.execute();
                    }
                }
                return null;
            });
        }
    }

    private IndicadorResponse indicador(String codImovel, String sigla) {
        List<IndicadorResponse> indicadores = service.buscarPorImovel(codImovel).indicadores();
        return indicadores.stream().filter(i -> i.indicador().equals(sigla)).findFirst().orElseThrow();
    }

    private long versao(String conjunto, Long execucao) {
        Integer proxima = jdbc.queryForObject(
                "SELECT NVL(MAX(versao), 0) + 1 FROM dataset_versao WHERE conjunto = ?", Integer.class, conjunto);
        jdbc.update("INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona) VALUES (?, ?, ?, 'TRATADA')",
                conjunto, proxima, execucao);
        return jdbc.queryForObject("SELECT id FROM dataset_versao WHERE conjunto = ? AND versao = ?",
                Long.class, conjunto, proxima);
    }

    private void imovel(String cod, String municipio, String areaHa,
                        double x1, double y1, double x2, double y2, long versao) {
        jdbc.update("INSERT INTO imovel_rural (cod_imovel, municipio, estado, area_ha, geometria, dataset_versao_id)"
                + " VALUES (?, ?, 'PR', ?, " + retangulo(x1, y1, x2, y2) + ", ?)",
                cod, municipio, new java.math.BigDecimal(areaHa), versao);
    }

    private void reservaLegal(String cod, String areaHa, double x1, double y1, double x2, double y2, long versao) {
        jdbc.update("INSERT INTO car_reserva_legal (cod_imovel, municipio, area_ha, geometria, dataset_versao_id)"
                + " VALUES (?, 'Municipio Teste Indicadores', ?, " + retangulo(x1, y1, x2, y2) + ", ?)",
                cod, new java.math.BigDecimal(areaHa), versao);
    }

    private void foco(String id, LocalDateTime quando, String satelite, double lon, double lat, long versao) {
        jdbc.update("INSERT INTO foco_calor (id_inpe, data_hora, satelite, geometria, dataset_versao_id)"
                + " VALUES (?, ?, ?, SDO_GEOMETRY(2001, 4326, SDO_POINT_TYPE(?, ?, NULL), NULL, NULL), ?)",
                id, Timestamp.valueOf(quando), satelite, lon, lat, versao);
    }

    /** Anel externo anti-horário (SW, SE, NE, NW), como o Oracle espera. */
    private static String retangulo(double x1, double y1, double x2, double y2) {
        return "SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 1), SDO_ORDINATE_ARRAY("
                + x1 + "," + y1 + "," + x2 + "," + y1 + "," + x2 + "," + y2 + "," + x1 + "," + y2 + "," + x1 + "," + y1 + "))";
    }
}
