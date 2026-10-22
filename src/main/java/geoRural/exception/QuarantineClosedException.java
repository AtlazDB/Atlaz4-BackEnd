package geoRural.exception;

/** Registro de quarentena DESCARTADO ou REVALIDADO: a análise já acabou. */
public class QuarantineClosedException extends RuntimeException {
    public QuarantineClosedException(Long id, String status) {
        super("O registro de quarentena " + id + " está " + status + " e não aceita mais alterações.");
    }
}
