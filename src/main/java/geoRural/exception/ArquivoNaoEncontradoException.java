package geoRural.exception;

public class ArquivoNaoEncontradoException extends RuntimeException {
    public ArquivoNaoEncontradoException(Long id) {
        super("Arquivo bruto não encontrado: " + id);
    }
}
