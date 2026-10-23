package geoRural.exception;

/**
 * O pkg_tratada recusou gravar o registro na Zona Tratada (ORA-20010 a ORA-20014): já
 * encerrado, ainda reprovado, fonte sem mapeamento ou dado mais novo já gravado.
 * A mensagem é a do pacote, escrita para o operador.
 */
public class GravacaoNaoPermitidaException extends RuntimeException {
    public GravacaoNaoPermitidaException(String mensagem) {
        super(mensagem);
    }
}
