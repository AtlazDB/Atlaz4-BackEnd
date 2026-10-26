package geoRural.service;

import geoRural.entity.Fonte;
import geoRural.exception.MapeamentoDuplicadoException;
import geoRural.mapeamento.dto.MapeamentoRequest;
import geoRural.mapeamento.entity.FonteColuna;
import geoRural.mapeamento.entity.RegraCampo;
import geoRural.mapeamento.repository.DominioRepository;
import geoRural.mapeamento.repository.FonteColunaRepository;
import geoRural.mapeamento.repository.RegraCampoRepository;
import geoRural.mapeamento.service.DestinoService;
import geoRural.mapeamento.service.MapeamentoService;
import geoRural.mapeamento.service.MapeamentoServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A edição de mapeamento (PUT /fontes/{id}/mapeamentos/{id}), sem banco: só as regras do service. */
class MapeamentoEdicaoTest {

    private final FonteService fonteService = mock(FonteService.class);
    private final RegraCampoRepository regraRepository = mock(RegraCampoRepository.class);
    private final MapeamentoService service = new MapeamentoServiceImpl(fonteService, regraRepository,
            mock(FonteColunaRepository.class), mock(DominioRepository.class), mock(DestinoService.class));
    private FonteColuna coluna;
    private RegraCampo existente;

    @BeforeEach
    void regraGravada() {
        coluna = mock(FonteColuna.class);
        when(coluna.getId()).thenReturn(10L);
        existente = new RegraCampo();
        existente.setFonteColuna(coluna);
        existente.setDestinoTabela("foco_calor");
        existente.setCampoDestino("frp");
        existente.setTipoDado("DECIMAL");
        when(fonteService.buscarPorId(7L)).thenReturn(new Fonte());
        when(regraRepository.buscarDaFonte(3L, 7L)).thenReturn(Optional.of(existente));
        when(regraRepository.saveAndFlush(any(RegraCampo.class))).thenAnswer(chamada -> chamada.getArgument(0));
        // O próprio mapeamento já ocupa foco_calor.frp.
        when(regraRepository.existePorDestino(7L, "foco_calor", "frp")).thenReturn(true);
    }

    @Test
    void editaAsRegrasEMantemAColuna() {
        RegraCampo r = service.atualizar(7L, 3L, pedido(999L, "foco_calor", "frp", "DECIMAL", "VIRGULA", true));

        assertThat(r.getFonteColuna()).isSameAs(coluna);
        assertThat(r.getFormatoOrigem()).isEqualTo("VIRGULA");
        assertThat(r.isObrigatorio()).isTrue();
    }

    @Test
    void trocaODestinoParaUmCampoLivre() {
        RegraCampo r = service.atualizar(7L, 3L, pedido(null, "foco_calor", "bioma", "TEXTO", null, false));

        assertThat(r.getCampoDestino()).isEqualTo("bioma");
        assertThat(r.getTipoDado()).isEqualTo("TEXTO");
    }

    @Test
    void destinoDeOutraColunaE409() {
        when(regraRepository.existePorDestino(7L, "foco_calor", "satelite")).thenReturn(true);

        assertThatThrownBy(() -> service.atualizar(7L, 3L, pedido(null, "foco_calor", "satelite", "TEXTO", null, false)))
                .isInstanceOf(MapeamentoDuplicadoException.class)
                .hasMessageContaining("já recebe outra coluna");
    }

    private static MapeamentoRequest pedido(Long colunaId, String tabela, String campo, String tipo,
                                            String formato, boolean obrigatorio) {
        return new MapeamentoRequest(colunaId, tabela, campo, tipo, formato, obrigatorio, false, false, false,
                null, false, null, null);
    }
}
