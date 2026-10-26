package geoRural.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import geoRural.dto.ProcessamentoResponse;
import geoRural.exception.ProcessamentoEmAndamentoException;
import geoRural.service.GravadorImoveis.ArquivoParaProcessar;

/** Com AIRFLOW_HABILITADO=true o back-end não processa: só devolve o arquivo (de qualquer fonte) para a fila do Airflow. */
class ProcessamentoCarAirflowTest {

    private GravadorImoveis gravador;
    private ObjectStorageService objectStorage;
    private ProcessamentoCarServiceImpl servico;

    @BeforeEach
    void preparar() {
        gravador = mock(GravadorImoveis.class);
        objectStorage = mock(ObjectStorageService.class);
        servico = new ProcessamentoCarServiceImpl(gravador, mock(LeitorShapefile.class), objectStorage,
                mock(TransactionTemplate.class), true);
        when(gravador.buscarArquivo(1L)).thenReturn(Optional.of(
                new ArquivoParaProcessar(1L, "bruta/CAR/2026-10-18/abc_car.zip", "car.zip", "REJEITADO", "CAR")));
    }

    @Test
    void devolveParaAFilaSemProcessar() {
        when(gravador.marcarRecebido(1L)).thenReturn(true);

        ProcessamentoResponse resposta = servico.processar(1L);

        assertThat(resposta.status()).isEqualTo("RECEBIDO");
        verify(gravador, never()).marcarProcessando(anyLong());
        verify(gravador, never()).iniciarExecucao(anyLong());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void qualquerFonteVaiParaAFilaDoAirflow() {
        when(gravador.buscarArquivo(2L)).thenReturn(Optional.of(new ArquivoParaProcessar(
                2L, "bruta/MUN-IAT/2026-10-24/abc_municipios.zip", "municipios.zip", "AGUARDANDO_MAPEAMENTO", "MUN-IAT")));
        when(gravador.marcarRecebido(2L)).thenReturn(true);
        ProcessamentoArquivoServiceImpl roteador = new ProcessamentoArquivoServiceImpl(gravador,
                mock(ProcessamentoCarService.class), mock(ProcessamentoReservaLegalService.class),
                mock(ProcessamentoFocosService.class), true);

        assertThat(roteador.processar(2L).status()).isEqualTo("RECEBIDO");
    }

    @Test
    void recusaQuandoJaEstaProcessando() {
        when(gravador.marcarRecebido(1L)).thenReturn(false);

        assertThatThrownBy(() -> servico.processar(1L)).isInstanceOf(ProcessamentoEmAndamentoException.class);
    }
}
