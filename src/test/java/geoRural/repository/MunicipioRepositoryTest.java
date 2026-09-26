package geoRural.repository;

import geoRural.entity.Municipio;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roda contra o Oracle do docker-compose: SDO_GEOMETRY e SDO_RELATE não existem
 * em banco embutido. Por isso o replace = NONE — sem ele o slice trocaria o
 * datasource por um H2. O ddl-auto fica explícito em none para o Hibernate não
 * encostar no schema que o Flyway monta.
 *
 * Cada teste roda em transação com rollback no fim, então o município de teste
 * não fica no banco.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class MunicipioRepositoryTest {

    private static final int SRID_WGS84 = 4326;

    /** Não colide com o 4113700 de Londrina, inserido pela V5. */
    private static final String COD_IBGE_TESTE = "9999999";
    private static final String COD_IBGE_LONDRINA = "4113700";

    private final GeometryFactory fabrica = new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    @Autowired
    private MunicipioRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void gravaELeDeVoltaAMesmaGeometriaComOMesmoSrid() {
        Polygon poligono = retangulo(-49.30, -25.45, -49.05, -25.20);

        Municipio municipio = new Municipio();
        municipio.setCodIbge(COD_IBGE_TESTE);
        municipio.setNome("Municipio de Teste");
        municipio.setEstado("PR");
        municipio.setRegiao("Sul");
        municipio.setAreaKm2(new BigDecimal("123.4500"));
        municipio.setGeometria(poligono);
        municipio.setDatasetVersaoId(versaoDeMunicipios());

        repository.saveAndFlush(municipio);

        // Sem limpar o contexto, o find devolveria o objeto que já está em
        // memória e o teste não provaria nada sobre a ida ao banco.
        entityManager.clear();

        Municipio lido = repository.findByCodIbge(COD_IBGE_TESTE).orElseThrow();

        assertThat(lido.getGeometria()).isNotNull();
        assertThat(lido.getGeometria().getSRID()).isEqualTo(SRID_WGS84);
        assertThat(lido.getGeometria().equalsTopo(poligono)).isTrue();
        assertThat(lido.getNome()).isEqualTo("Municipio de Teste");
        assertThat(lido.getEstado()).isEqualTo("PR");
    }

    @Test
    void encontraLondrinaPorUmPontoDentroDoPoligono() {
        // Centro do retângulo que a V5 gravou para Londrina
        // (-51.30..-51.05 de longitude, -23.45..-23.20 de latitude).
        List<Municipio> encontrados = repository.buscarPorPonto(-51.175, -23.325);

        assertThat(encontrados)
                .extracting(Municipio::getCodIbge)
                .contains(COD_IBGE_LONDRINA);
    }

    @Test
    void naoEncontraMunicipioParaPontoForaDaMalha() {
        // Meio do Atlântico: nenhum município cobre este ponto.
        List<Municipio> encontrados = repository.buscarPorPonto(-30.0, -20.0);

        assertThat(encontrados).isEmpty();
    }

    /** A versão do conjunto "municipios" que a V5 criou; a FK exige uma existente. */
    private Long versaoDeMunicipios() {
        Number id = (Number) entityManager.getEntityManager()
                .createNativeQuery("""
                        SELECT id FROM dataset_versao
                         WHERE conjunto = 'municipios'
                         ORDER BY versao FETCH FIRST 1 ROWS ONLY
                        """)
                .getSingleResult();
        return id.longValue();
    }

    /** Anel externo anti-horário (SW, SE, NE, NW), como o Oracle espera em SRID geodésico. */
    private Polygon retangulo(double oeste, double sul, double leste, double norte) {
        return fabrica.createPolygon(new Coordinate[]{
                new Coordinate(oeste, sul),
                new Coordinate(leste, sul),
                new Coordinate(leste, norte),
                new Coordinate(oeste, norte),
                new Coordinate(oeste, sul)
        });
    }
}
