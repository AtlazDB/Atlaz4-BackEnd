package geoRural.exception;

public class SiglaDuplicadaException extends RuntimeException {
    public SiglaDuplicadaException(String sigla) {
        super("Já existe uma fonte com a sigla \"" + sigla + "\".");
    }
}
