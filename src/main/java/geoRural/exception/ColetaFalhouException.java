package geoRural.exception;

/**
 * A coleta por link não conseguiu trazer o arquivo: o servidor de origem não respondeu, respondeu
 * com erro ou mandou mais do que o limite. A mensagem é escrita para o operador.
 */
public class ColetaFalhouException extends RuntimeException {
    public ColetaFalhouException(String mensagem) {
        super(mensagem);
    }

    public ColetaFalhouException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
