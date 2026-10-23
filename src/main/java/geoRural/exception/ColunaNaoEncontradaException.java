package geoRural.exception;

public class ColunaNaoEncontradaException extends RuntimeException {
    public ColunaNaoEncontradaException() {
        super("Coluna não encontrada nesta fonte.");
    }
}