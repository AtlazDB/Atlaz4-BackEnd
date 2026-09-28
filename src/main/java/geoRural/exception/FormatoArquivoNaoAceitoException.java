package geoRural.exception;

import java.util.Collection;

public class FormatoArquivoNaoAceitoException extends RuntimeException {
    public FormatoArquivoNaoAceitoException(String extensao, Collection<String> aceitas) {
        super("Formato \"" + extensao + "\" não aceito. Use: " + String.join(", ", aceitas) + ".");
    }
}
