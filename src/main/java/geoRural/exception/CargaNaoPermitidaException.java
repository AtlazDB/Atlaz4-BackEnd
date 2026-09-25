package geoRural.exception;

public class CargaNaoPermitidaException extends RuntimeException {
    public CargaNaoPermitidaException(Long arquivoId, String status) {
        super("O arquivo " + arquivoId + " está com status " + status + " e não pode ser carregado.");
    }
}
