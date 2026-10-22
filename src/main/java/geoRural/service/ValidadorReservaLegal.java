package geoRural.service;

import java.math.BigDecimal;

import org.locationtech.jts.geom.Geometry;

import geoRural.dto.ImovelLido;
import geoRural.service.ValidadorImovel.Resultado;

/**
 * Valida os polígonos de Reserva Legal de um arquivo. Ao contrário do imóvel, o mesmo cod_imovel
 * pode aparecer várias vezes: cada linha é um polígono de RL, e o IRL soma todos.
 */
public class ValidadorReservaLegal {

    public Resultado validar(ImovelLido rl) {
        if (rl.codImovel() == null || rl.codImovel().isBlank()) {
            return quarentena(rl, "cod_imovel vazio");
        }
        // A área declarada é o numerador do IRL: sem ela não há como calcular
        if (rl.areaHa() == null) {
            return quarentena(rl, "num_area vazio");
        }
        if (rl.areaHa().compareTo(BigDecimal.ZERO) < 0) {
            return quarentena(rl, "num_area negativo");
        }

        Geometry g = rl.geometria();
        if (g == null || g.isEmpty()) {
            return quarentena(rl, "sem geometria");
        }
        g = ValidadorImovel.prepararGeometria(g);
        if (g == null) {
            return quarentena(rl, "geometria irreparável");
        }
        return new Resultado(rl, g, null);
    }

    private static Resultado quarentena(ImovelLido rl, String motivo) {
        return new Resultado(rl, null, motivo);
    }
}
