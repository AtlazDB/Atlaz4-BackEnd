package geoRural.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import geoRural.dto.FocoLido;
import geoRural.exception.CsvInvalidoException;

/**
 * Lê o CSV de focos do INPE (dataserver-coids.inpe.br, pastas diario/ e mensal/), linha a linha:
 * o mensal do Brasil passa de 100 MB e não cabe inteiro na memória com folga.
 *
 * Aceita o .csv puro ou dentro de um .zip — compactado, o mesmo mês sobe para o bucket com um
 * quinto do tamanho. O tipo é reconhecido pelo conteúdo, não pelo nome: o arquivo baixado da zona
 * bruta sempre chega com extensão .zip.
 */
@Component
public class LeitorFocosInpe {

    static final List<String> COLUNAS_OBRIGATORIAS = List.of("id", "lat", "lon", "data_hora_gmt", "satelite");

    public void ler(Path arquivo, Consumer<FocoLido> consumidor) {
        try (InputStream entrada = abrir(arquivo);
             BufferedReader leitor = new BufferedReader(new InputStreamReader(entrada, StandardCharsets.UTF_8))) {

            String cabecalho = leitor.readLine();
            if (cabecalho == null) {
                throw new CsvInvalidoException("O arquivo de focos está vazio.");
            }
            List<String> colunas = separar(semBom(cabecalho)).stream()
                    .map(c -> c.trim().toLowerCase(Locale.ROOT))
                    .toList();
            List<String> faltando = COLUNAS_OBRIGATORIAS.stream().filter(c -> !colunas.contains(c)).toList();
            if (!faltando.isEmpty()) {
                throw new CsvInvalidoException("O CSV não tem as colunas do INPE: " + String.join(", ", faltando)
                        + ". Use os arquivos das pastas diario/ ou mensal/ do dataserver do INPE.");
            }

            String linha;
            int numero = 1;
            while ((linha = leitor.readLine()) != null) {
                numero++;
                if (linha.isBlank()) {
                    continue;
                }
                Map<String, String> valores = valores(colunas, separar(linha));
                consumidor.accept(new FocoLido(numero,
                        valores.get("id"), valores.get("lat"), valores.get("lon"),
                        valores.get("data_hora_gmt"), valores.get("satelite"),
                        valores.get("municipio"), valores.get("municipio_id"), valores.get("estado"),
                        valores.get("bioma"), valores.get("frp"), valores));
            }
        } catch (IOException e) {
            throw new CsvInvalidoException("Erro ao ler o arquivo de focos: " + e.getMessage(), e);
        }
    }

    private static InputStream abrir(Path arquivo) throws IOException {
        if (!ehZip(arquivo)) {
            return Files.newInputStream(arquivo);
        }
        ZipInputStream zip = new ZipInputStream(Files.newInputStream(arquivo));
        ZipEntry entrada;
        while ((entrada = zip.getNextEntry()) != null) {
            if (!entrada.isDirectory() && entrada.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                return zip;
            }
        }
        zip.close();
        throw new CsvInvalidoException("Nenhum arquivo .csv encontrado dentro do zip.");
    }

    /** Todo zip começa com "PK\3\4". */
    private static boolean ehZip(Path arquivo) throws IOException {
        try (InputStream in = Files.newInputStream(arquivo)) {
            byte[] inicio = in.readNBytes(4);
            return inicio.length == 4 && inicio[0] == 'P' && inicio[1] == 'K' && inicio[2] == 3 && inicio[3] == 4;
        }
    }

    private static String semBom(String texto) {
        return texto.startsWith("﻿") ? texto.substring(1) : texto;
    }

    private static Map<String, String> valores(List<String> colunas, List<String> campos) {
        Map<String, String> valores = new LinkedHashMap<>();
        for (int i = 0; i < colunas.size(); i++) {
            String valor = i < campos.size() ? campos.get(i).trim() : "";
            valores.put(colunas.get(i), valor.isEmpty() ? null : valor);
        }
        return valores;
    }

    /** Separa uma linha por vírgula, respeitando campos entre aspas (que podem ter vírgula dentro). */
    static List<String> separar(String linha) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean entreAspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                if (entreAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else {
                    entreAspas = !entreAspas;
                }
            } else if (c == ',' && !entreAspas) {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString());
        return campos;
    }
}
