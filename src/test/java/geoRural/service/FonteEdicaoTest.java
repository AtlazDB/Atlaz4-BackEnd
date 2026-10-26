package geoRural.service;

import geoRural.dto.FonteRequest;
import geoRural.entity.Fonte;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.FonteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A edição de fonte (PUT /fontes/{id}), sem banco: só as regras do FonteServiceImpl. */
class FonteEdicaoTest {

    private final FonteRepository repository = mock(FonteRepository.class);
    private final FonteService service = new FonteServiceImpl(repository);
    private Fonte existente;

    @BeforeEach
    void fonteGravada() {
        existente = new Fonte();
        existente.setNome("Municípios");
        existente.setSigla("MUN-IAT");
        existente.setOrgao("IAT");
        existente.setFormato("GeoJSON");
        existente.setModalidade("LINK");
        existente.setUrlOrigem("https://geopr.iat.pr.gov.br/server/rest/services/m/FeatureServer/0/query?where=1=1");
        existente.setMetodoHttp("GET");
        existente.setCabecalhos("{\"Accept\": \"application/json\"}");
        when(repository.findById(7L)).thenReturn(Optional.of(existente));
        when(repository.save(any(Fonte.class))).thenAnswer(chamada -> chamada.getArgument(0));
    }

    @Test
    void editaOsDadosEMantemOsCabecalhosQueAApiNaoDevolve() {
        Fonte f = service.atualizar(7L, pedido(null, "LINK", "https://novo.gov.br/arcgis/rest/services/x/FeatureServer/0/query"));

        assertThat(f.getNome()).isEqualTo("Municípios do Paraná (IAT)");
        assertThat(f.getSigla()).isEqualTo("MUN-IAT");
        assertThat(f.getUrlOrigem()).isEqualTo("https://novo.gov.br/arcgis/rest/services/x/FeatureServer/0/query");
        assertThat(f.getCabecalhos()).isEqualTo("{\"Accept\": \"application/json\"}");
    }

    @Test
    void siglaNaoMuda() {
        assertThatThrownBy(() -> service.atualizar(7L, pedido("OUTRA", "LINK", "https://x.gov.br/a.zip")))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("sigla não pode ser alterada");
        // A mesma sigla, em minúsculas, é aceita.
        assertThat(service.atualizar(7L, pedido("mun-iat", "LINK", "https://x.gov.br/a.zip")).getSigla()).isEqualTo("MUN-IAT");
    }

    @Test
    void virarUploadApagaOLinkEOPedidoDeColeta() {
        existente.setColetaSolicitadaEm(OffsetDateTime.now());

        Fonte f = service.atualizar(7L, pedido(null, "MANUAL", null));

        assertThat(f.getModalidade()).isEqualTo("MANUAL");
        assertThat(f.getUrlOrigem()).isNull();
        assertThat(f.getCabecalhos()).isNull();
        assertThat(f.getColetaSolicitadaEm()).isNull();
    }

    @Test
    void virarLinkSemLinkERecusado() {
        existente.setModalidade("MANUAL");
        existente.setUrlOrigem(null);

        assertThatThrownBy(() -> service.atualizar(7L, pedido(null, "LINK", " ")))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("precisa do link");
    }

    private static FonteRequest pedido(String sigla, String modalidade, String url) {
        return new FonteRequest("Municípios do Paraná (IAT)", sigla, "Instituto Água e Terra", "GeoJSON", "anual",
                modalidade, url, "GET", null, null, null, null, null);
    }
}
