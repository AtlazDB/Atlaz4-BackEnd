package geoRural.exception;

public class ProcessamentoEmAndamentoException extends RuntimeException {
    public ProcessamentoEmAndamentoException(Long arquivoId) {
        super("O arquivo " + arquivoId + " já está sendo processado.");
    }
}
