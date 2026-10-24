package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import geoRural.entity.RegistroQuarentena;

import java.util.List;

/** Resultado da revalidação: o status, os erros que sobraram e o registro como ficou. */
public record RevalidationResponse(
    String status,
    @JsonProperty("erros") List<RevalidationError> errors,
    RegistroQuarentena quarantine
) {
    public boolean approved() {
        return "APROVADO".equals(status);
    }
}
