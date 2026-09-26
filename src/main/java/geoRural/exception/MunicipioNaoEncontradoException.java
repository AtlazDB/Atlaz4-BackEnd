package geoRural.exception;

public class MunicipioNaoEncontradoException extends RuntimeException {
    public MunicipioNaoEncontradoException(String codIbge) {
        super("Município não encontrado: " + codIbge);
    }
}
