package geoRural.exception;

import geoRural.dto.ErroResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ImovelNaoEncontradoException.class)
    public ResponseEntity<String> handleNaoEncontrado(ImovelNaoEncontradoException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(FonteNaoEncontradaException.class)
    public ResponseEntity<ErroResponse> handleFonteNaoEncontrada(FonteNaoEncontradaException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErroResponse.de("Fonte não encontrada."));
    }

    @ExceptionHandler(SiglaDuplicadaException.class)
    public ResponseEntity<ErroResponse> handleSiglaDuplicada(SiglaDuplicadaException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErroResponse.de(ex.getMessage()));
    }

    @ExceptionHandler(RequisicaoInvalidaException.class)
    public ResponseEntity<ErroResponse> handleRequisicaoInvalida(RequisicaoInvalidaException ex) {
        return ResponseEntity.badRequest().body(new ErroResponse(ex.getMessage(), ex.getCampos()));
    }

    @ExceptionHandler(FormatoArquivoNaoAceitoException.class)
    public ResponseEntity<ErroResponse> handleFormatoNaoAceito(FormatoArquivoNaoAceitoException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErroResponse.de(ex.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErroResponse> handleSemArquivo(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest().body(ErroResponse.de("Nenhum arquivo enviado."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErroResponse> handleArquivoGrande(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErroResponse.de("Arquivo maior que o limite de 200 MB."));
    }
}