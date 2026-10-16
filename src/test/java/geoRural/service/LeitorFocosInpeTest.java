package geoRural.service;

import geoRural.dto.FocoLido;
import geoRural.exception.CsvInvalidoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeitorFocosInpeTest {

    private final LeitorFocosInpe leitor = new LeitorFocosInpe();

    @TempDir
    Path tmp;

    @Test
    void separaRespeitandoVirgulaEntreAspas() {
        assertThat(LeitorFocosInpe.separar("a,\"Lençóis, BA\",c,\"diz \"\"oi\"\"\""))
                .containsExactly("a", "Lençóis, BA", "c", "diz \"oi\"");
    }

    @Test
    void leAsColunasPeloNomeEmQualquerOrdemEComBom() throws IOException {
        Path csv = tmp.resolve("focos.csv");
        Files.writeString(csv, "﻿satelite,id,lon,lat,data_hora_gmt,municipio_id\n"
                + "AQUA_M-T,abc, -50.1, -25.1,2026-09-10 15:12:00,4119905\n", StandardCharsets.UTF_8);

        List<FocoLido> lidos = new ArrayList<>();
        leitor.ler(csv, lidos::add);

        assertThat(lidos).hasSize(1);
        FocoLido f = lidos.get(0);
        assertThat(f.idInpe()).isEqualTo("abc");
        assertThat(f.lon()).isEqualTo("-50.1");
        assertThat(f.satelite()).isEqualTo("AQUA_M-T");
        assertThat(f.codIbge()).isEqualTo("4119905");
        assertThat(f.linha()).isEqualTo(2);
    }

    @Test
    void recusaArquivoSemAsColunasDoInpe() throws IOException {
        Path csv = tmp.resolve("outro.csv");
        Files.writeString(csv, "codigo,nome\n1,x\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> leitor.ler(csv, f -> {}))
                .isInstanceOf(CsvInvalidoException.class)
                .hasMessageContaining("id, lat, lon, data_hora_gmt, satelite");
    }
}
