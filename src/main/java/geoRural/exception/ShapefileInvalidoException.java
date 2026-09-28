package geoRural.exception;

/**
 * O arquivo enviado não pode ser processado como um todo (zip corrompido, sem .shp,
 * faltando .shx/.dbf/.prj, sem a coluna do código do imóvel...). Leva o arquivo_bruto a REJEITADO.
 */
public class ShapefileInvalidoException extends RuntimeException {
    public ShapefileInvalidoException(String mensagem) {
        super(mensagem);
    }

    public ShapefileInvalidoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
