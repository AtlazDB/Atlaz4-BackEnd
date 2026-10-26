package geoRural.service;

import geoRural.dto.FonteRequest;
import geoRural.entity.Fonte;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.FonteRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** O cadastro de fonte por link, sem banco: só as regras do FonteServiceImpl. */
class FonteLinkValidacaoTest {

    private final FonteRepository repository = mock(FonteRepository.class);
    private final FonteService service = new FonteServiceImpl(repository);

    FonteLinkValidacaoTest() {
        when(repository.save(any(Fonte.class))).thenAnswer(chamada -> chamada.getArgument(0));
    }

    @Test
    void fonteManualNaoPrecisaDeLink() {
        assertThat(service.cadastrar(fonte("MANUAL", null, null)).getModalidade()).isEqualTo("MANUAL");
    }

    @Test
    void fontePorLinkGuardaOLinkEOMetodo() {
        Fonte f = service.cadastrar(fonte("link", " https://geoserver.car.gov.br/geoserver/sicar/ows?service=WFS ", "post"));

        assertThat(f.getModalidade()).isEqualTo("LINK");
        assertThat(f.getUrlOrigem()).isEqualTo("https://geoserver.car.gov.br/geoserver/sicar/ows?service=WFS");
        assertThat(f.getMetodoHttp()).isEqualTo("POST");
    }

    @Test
    void fontePorLinkSemLinkERecusada() {
        assertThatThrownBy(() -> service.cadastrar(fonte("LINK", " ", null)))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("precisa do link");
    }

    @Test
    void linkQueNaoEHttpERecusado() {
        assertThatThrownBy(() -> service.cadastrar(fonte("LINK", "ftp://dados.gov.br/x.zip", null)))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("http://");
        assertThatThrownBy(() -> service.cadastrar(fonte("LINK", "dados.gov.br/x.zip", null)))
                .isInstanceOf(RequisicaoInvalidaException.class);
    }

    @Test
    void modalidadeEMetodoInvalidosSaoRecusados() {
        assertThatThrownBy(() -> service.cadastrar(fonte("FTP", "https://x.gov.br/a.zip", null)))
                .isInstanceOf(RequisicaoInvalidaException.class).hasMessageContaining("Modalidade");
        assertThatThrownBy(() -> service.cadastrar(fonte("LINK", "https://x.gov.br/a.zip", "DELETE")))
                .isInstanceOf(RequisicaoInvalidaException.class).hasMessageContaining("Método");
    }

    private static FonteRequest fonte(String modalidade, String url, String metodo) {
        return new FonteRequest("Fonte de teste", "TESTE", "Órgão", "SHP", "mensal",
                modalidade, url, metodo, null, null, null, null, null);
    }
}
