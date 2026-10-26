package geoRural.mapeamento.service;

import geoRural.mapeamento.dto.MapeamentoRequest;
import geoRural.mapeamento.entity.RegraCampo;

import java.util.List;

public interface MapeamentoService {

    /** Regras da fonte. Lança FonteNaoEncontradaException se a fonte não existir. */
    List<RegraCampo> listar(Long fonteId);

    /**
     * Cria o mapeamento de uma coluna da fonte.
     *
     * 404: fonte ou coluna (da fonte) não existe.
     * 400: dados inválidos (campos obrigatórios, tipo, chave sem obrigatório, política de geometria,
     *      referência cruzada, domínio inexistente ou inativo, nomes de tabela e campo inválidos).
     * 409: a coluna já está mapeada, ou o campo de destino já recebe outra coluna da fonte.
     */
    RegraCampo criar(Long fonteId, MapeamentoRequest request);

    /**
     * Edita o mapeamento: destino e regras. A coluna de origem não muda (colunaId do corpo é ignorado).
     * Vale a partir do próximo processamento; o que já foi carregado não é refeito.
     *
     * 404: fonte ou mapeamento (da fonte) não existe.
     * 400: as mesmas validações do criar.
     * 409: o novo campo de destino já recebe outra coluna da fonte.
     */
    RegraCampo atualizar(Long fonteId, Long mapeamentoId, MapeamentoRequest request);

    /**
     * Remove o mapeamento; a coluna volta a aparecer como "nova".
     * 404: fonte ou mapeamento (da fonte) não existe.
     */
    void remover(Long fonteId, Long mapeamentoId);
}