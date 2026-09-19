package geoRural.exception;

import java.util.List;

public class RequisicaoInvalidaException extends RuntimeException {

    private final List<String> campos;

    public RequisicaoInvalidaException(String mensagem) {
        this(mensagem, null);
    }

    public RequisicaoInvalidaException(String mensagem, List<String> campos) {
        super(mensagem);
        this.campos = campos;
    }

    public List<String> getCampos() {
        return campos;
    }
}
