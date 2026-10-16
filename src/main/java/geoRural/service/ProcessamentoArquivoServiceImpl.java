package geoRural.service;

import org.springframework.stereotype.Service;

import geoRural.dto.ProcessamentoResponse;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.service.GravadorImoveis.ArquivoParaProcessar;

/**
 * Escolhe o processamento pela sigla da fonte do arquivo: CAR (imóveis), CAR-RL (Reserva Legal)
 * ou INPE-FOCOS (focos de calor). Cada um confere o próprio formato.
 */
@Service
public class ProcessamentoArquivoServiceImpl implements ProcessamentoArquivoService {

    private final GravadorImoveis gravador;
    private final ProcessamentoCarService imoveis;
    private final ProcessamentoReservaLegalService reservaLegal;
    private final ProcessamentoFocosService focos;

    public ProcessamentoArquivoServiceImpl(GravadorImoveis gravador,
                                           ProcessamentoCarService imoveis,
                                           ProcessamentoReservaLegalService reservaLegal,
                                           ProcessamentoFocosService focos) {
        this.gravador = gravador;
        this.imoveis = imoveis;
        this.reservaLegal = reservaLegal;
        this.focos = focos;
    }

    @Override
    public ProcessamentoResponse processar(Long arquivoId) {
        ArquivoParaProcessar arquivo = gravador.buscarArquivo(arquivoId)
                .orElseThrow(() -> new ArquivoNaoEncontradoException(arquivoId));

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
