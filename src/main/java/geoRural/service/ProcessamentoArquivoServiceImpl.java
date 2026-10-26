package geoRural.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import geoRural.dto.ProcessamentoResponse;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.ProcessamentoEmAndamentoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.service.GravadorImoveis.ArquivoParaProcessar;

/**
 * Com o Airflow ligado (AIRFLOW_HABILITADO), qualquer arquivo volta para a fila (RECEBIDO) e a DAG
 * processar_arquivo escolhe o caminho: o shapefile do CAR pelo caminho próprio, o resto pelo
 * genérico, guiado pelo mapeamento da fonte.
 *
 * Sem o Airflow, escolhe o processamento Java pela sigla da fonte do arquivo: CAR (imóveis), CAR-RL
 * (Reserva Legal) ou INPE-FOCOS (focos de calor). Cada um confere o próprio formato.
 */
@Service
public class ProcessamentoArquivoServiceImpl implements ProcessamentoArquivoService {

    private final GravadorImoveis gravador;
    private final ProcessamentoCarService imoveis;
    private final ProcessamentoReservaLegalService reservaLegal;
    private final ProcessamentoFocosService focos;
    private final boolean processarNoAirflow;

    public ProcessamentoArquivoServiceImpl(GravadorImoveis gravador,
                                           ProcessamentoCarService imoveis,
                                           ProcessamentoReservaLegalService reservaLegal,
                                           ProcessamentoFocosService focos,
                                           @Value("${app.processamento.airflow:false}") boolean processarNoAirflow) {
        this.gravador = gravador;
        this.imoveis = imoveis;
        this.reservaLegal = reservaLegal;
        this.focos = focos;
        this.processarNoAirflow = processarNoAirflow;
    }

    @Override
    public ProcessamentoResponse processar(Long arquivoId) {
        ArquivoParaProcessar arquivo = gravador.buscarArquivo(arquivoId)
                .orElseThrow(() -> new ArquivoNaoEncontradoException(arquivoId));

        if (processarNoAirflow) {
            // RECEBIDO é o que o varredor do Airflow procura: em até 2 minutos ele pega o arquivo.
            if (!gravador.marcarRecebido(arquivoId)) {
                throw new ProcessamentoEmAndamentoException(arquivoId);
            }
            return ProcessamentoResponse.agendado(arquivoId);
        }
        String sigla = arquivo.siglaFonte() == null ? "" : arquivo.siglaFonte().toUpperCase();
        return switch (sigla) {
            case "CAR" -> imoveis.processar(arquivoId);
            case ProcessamentoReservaLegalServiceImpl.SIGLA -> reservaLegal.processar(arquivoId);
            case ProcessamentoFocosServiceImpl.SIGLA -> focos.processar(arquivoId);
            default -> throw new RequisicaoInvalidaException(
                    "Não há processamento para arquivos da fonte " + arquivo.siglaFonte()
                            + ". Processam-se arquivos das fontes CAR, CAR-RL e INPE-FOCOS.");
        };
    }
}
