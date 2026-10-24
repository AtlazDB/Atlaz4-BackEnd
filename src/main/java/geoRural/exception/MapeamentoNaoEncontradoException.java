package geoRural.exception;

public class MapeamentoNaoEncontradoException extends RuntimeException {
    public MapeamentoNaoEncontradoException() {
        super("Mapeamento não encontrado.");
    }
}