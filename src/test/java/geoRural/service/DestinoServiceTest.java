package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.mapeamento.dto.DestinoResponse;
import geoRural.mapeamento.service.DestinoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Os destinos do mapeamento, lidos do Oracle do docker-compose (user_tab_columns). */
@SpringBootTest
class DestinoServiceTest {

    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private DestinoService destinos;

    @Test
    void listaAsQuatroTabelasComOsCamposETipos() {
        List<DestinoResponse> lista = destinos.listar();

        assertThat(lista).extracting(DestinoResponse::tabela)
                .containsExactly("imovel_rural", "car_reserva_legal", "foco_calor", "municipio");
        DestinoResponse rl = lista.get(1);
        assertThat(rl.campos()).extracting(DestinoResponse.Campo::nome)
                .contains("cod_imovel", "area_ha", "situacao", "geometria")
                .doesNotContain("id", "dataset_versao_id", "criado_em");
        assertThat(campo(rl, "area_ha").tipo()).isEqualTo("DECIMAL");
        assertThat(campo(rl, "area_ha").obrigatorio()).isTrue();
        assertThat(campo(rl, "geometria").tipo()).isEqualTo("GEOMETRIA");
        assertThat(campo(lista.get(2), "data_hora").tipo()).isEqualTo("DATA");
        // estado do município é obrigatório na tabela, mas o pkg_tratada preenche sozinho
        DestinoResponse.Campo estado = campo(lista.get(3), "estado");
        assertThat(estado.obrigatorio()).isFalse();
        assertThat(estado.observacao()).contains("código IBGE");
    }

    @Test
    void recusaTabelaECampoQueNaoExistem() {
        assertThatThrownBy(() -> destinos.validar("reserva_legal", "area_ha"))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("Tabela de destino inválida");
        assertThatThrownBy(() -> destinos.validar("car_reserva_legal", "num_area"))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("não tem o campo num_area");
        destinos.validar("car_reserva_legal", "area_ha"); // não lança
    }

    private static DestinoResponse.Campo campo(DestinoResponse destino, String nome) {
        return destino.campos().stream().filter(c -> c.nome().equals(nome)).findFirst().orElseThrow();
    }
}
