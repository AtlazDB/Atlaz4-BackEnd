package geoRural.dto;

import geoRural.entity.ArquivoBruto;

/**
 * Resultado do "Executar agora" de uma fonte por link. {@code resultado}:
 *   RECEBIDO     o arquivo foi para a zona bruta e espera o processamento, como num upload;
 *   SEM_MUDANCA  o conteúdo é igual ao da última coleta: nada foi gravado, e {@code arquivo}
 *                é o arquivo anterior, que continua valendo;
 *   SOLICITADA   com o Airflow ligado: o pedido ficou na fila da DAG coleta_fontes, que pagina
 *                o serviço e grava o arquivo; o resultado aparece na fonte (ultimaColetaMensagem).
 */
public record ColetaResponse(String resultado, String mensagem, ArquivoBrutoResponse arquivo) {

    public static ColetaResponse recebido(ArquivoBruto arquivo) {
        return new ColetaResponse("RECEBIDO",
                "Arquivo coletado e guardado na zona bruta: " + arquivo.getNomeOriginal() + ".",
                ArquivoBrutoResponse.de(arquivo));
    }

    public static ColetaResponse solicitada() {
        return new ColetaResponse("SOLICITADA",
                "Coleta na fila do Airflow: começa em até 2 minutos, e o resultado aparece na fonte.", null);
    }

    public static ColetaResponse semMudanca(ArquivoBruto ultimo) {
        return new ColetaResponse("SEM_MUDANCA",
                "O conteúdo é igual ao da última coleta (" + ultimo.getNomeOriginal() + "): nada foi gravado.",
                ArquivoBrutoResponse.de(ultimo));
    }
}
