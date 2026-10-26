package com.melanta.core.ai.prompt

/** Templates de chat que substituem o embutido no `.litertlm` quando ele é incompatível com o runtime. */
object ChatTemplates {

    /**
     * ChatML que aceita conteúdo como string ou como lista de partes. O LiteRT-LM 0.18 envia
     * partes, e o template embutido do Qwen3 4B Instruct 2507 só aceita string (falha com
     * "tried to use + operator on unsupported types string and sequence").
     */
    val CHATML_CONTENT_PARTS = """
        {%- macro text(content) -%}
        {%- if content is string -%}{{ content }}{%- else -%}{%- for item in content -%}{%- if item['type'] == 'text' -%}{{ item['text'] }}{%- endif -%}{%- endfor -%}{%- endif -%}
        {%- endmacro -%}
        {%- for message in messages -%}
        {%- set role = 'assistant' if message.role == 'model' else message.role -%}
        <|im_start|>{{ role }}
        {{ text(message.content) }}<|im_end|>
        {% endfor -%}
        {%- if add_generation_prompt -%}
        <|im_start|>assistant
        {% endif -%}
    """.trimIndent() + "\n"
}
