package geoRural.exception;

public class FonteNaoEncontradaException extends RuntimeException {
    public FonteNaoEncontradaException(Long id) {
        super("Fonte não encontrada: " + id);
    }
}
