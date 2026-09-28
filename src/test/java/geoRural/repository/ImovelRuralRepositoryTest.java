package geoRural.repository;

import geoRural.entity.ImovelRural;
import geoRural.entity.Municipio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contra o Oracle do docker-compose, como o MunicipioRepositoryTest. Usa o
 * vínculo que a V5 gravou: TESTE-001 -> Londrina (4113700).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class ImovelRuralRepositoryTest {

    @Autowired
    private ImovelRuralRepository repository;

    @Autowired
    private MunicipioRepository municipioRepository;

    @Test
    void encontraOsImoveisPeloMunicipioVinculado() {
        Municipio londrina = municipioRepository.findByCodIbge("4113700").orElseThrow();

        List<ImovelRural> imoveis = repository.findByMunicipioId(londrina.getId());

        assertThat(imoveis)
                .extracting(ImovelRural::getCodImovel)
                .contains("TESTE-001");
    }

    @Test
    void filtraPorEstado() {
        assertThat(repository.findByEstado("PR"))
                .extracting(ImovelRural::getCodImovel)
                .contains("TESTE-001");

        assertThat(repository.findByEstado("AM")).isEmpty();
    }
}
