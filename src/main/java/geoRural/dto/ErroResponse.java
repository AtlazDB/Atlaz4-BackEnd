package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErroResponse(String mensagem, List<String> campos) {

    public static ErroResponse de(String mensagem) {
        return new ErroResponse(mensagem, null);
    }
}
