package org.rundeck.api.generator;

import java.util.stream.Collectors;

import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.rundeck.api.domain.RundeckToken;

public class TokenGenerator extends BaseDocGenerator{
    RundeckToken token;

    public TokenGenerator(RundeckToken token) {
        this.token = token;
    }
    @Override
    public Element generateXmlElement() {
        Element rootElem = DocumentFactory.getInstance().createElement("user");
        rootElem.addAttribute("user", token.getUser());
        rootElem.addAttribute("roles", token.getRoles().stream().collect(Collectors.joining(",")));
        return rootElem;
    }

}
