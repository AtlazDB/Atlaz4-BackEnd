package geoRural.mapeamento.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Lê o JSON de exemplos de fonte_coluna.exemplos (um array de valores simples, como
 * ["AT","PE","CA"]) e devolve cada item como texto.
 *
 * - Texto vira o próprio texto (com escapes tratados); número e booleano viram o texto como vieram.
 * - null é ignorado.
 * - Entrada vazia, nula ou que não seja um array devolve lista vazia.
 * - Arrays e objetos aninhados não são suportados: a leitura para neles e devolve o que já leu.
 *
 * Existe para não depender da versão do Jackson do projeto. Se o projeto já usa um ObjectMapper,
 * este método pode ser trocado por readValue(json, List.class).
 */
public final class ExemplosJson {

    private ExemplosJson() {
    }

    public static List<String> paraLista(String json) {
        if (json == null) {
            return List.of();
        }
        String s = json.strip();
        if (s.isEmpty() || s.charAt(0) != '[') {
            return List.of();
        }

        List<String> itens = new ArrayList<>();
        int n = s.length();
        int i = 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ']') {
                break;
            }
            if (c == ',' || Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && s.charAt(i) != '"') {
                    char d = s.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        char e = s.charAt(++i);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                if (i + 4 < n) {
                                    try {
                                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                        i += 4;
                                    } catch (NumberFormatException ex) {
                                        sb.append('u');
                                    }
                                } else {
                                    sb.append('u');
                                }
                            }
                            default -> sb.append(e);
                        }
                    } else {
                        sb.append(d);
                    }
                    i++;
                }
                if (i >= n) {
                    break; // texto sem aspas de fechamento (JSON truncado): descarta o pedaço incompleto
                }
                itens.add(sb.toString());
                i++;
            } else if (c == '[' || c == '{') {
                break;
            } else {
                int inicio = i;
                while (i < n && s.charAt(i) != ',' && s.charAt(i) != ']') {
                    i++;
                }
                String token = s.substring(inicio, i).strip();
                if (!token.isEmpty() && !token.equals("null")) {
                    itens.add(token);
                }
            }
        }
        return List.copyOf(itens);
    }
}