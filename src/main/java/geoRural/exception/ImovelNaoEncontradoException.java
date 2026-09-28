package geoRural.exception;

public class ImovelNaoEncontradoException extends RuntimeException {
    public ImovelNaoEncontradoException(String codImovel) {
        super("Imóvel não encontrado: " + codImovel);
    }
}