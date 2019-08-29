package org.rundeck.api.parser;

import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.dom4j.Node;
import org.rundeck.api.domain.RundeckToken;

public class RundeckTokenParserV19 extends BaseXpathParser<RundeckToken>{
    public RundeckTokenParserV19(final String xpath) {
        super(xpath);
    }

    public RundeckTokenParserV19() {

    }

    @Override
    public RundeckToken parse(Node targetNode) {
        RundeckToken rundeckToken = new RundeckToken();
        rundeckToken.setId(targetNode.valueOf("@id"));
        rundeckToken.setUser(targetNode.valueOf("@user"));
        rundeckToken.setCreator(targetNode.valueOf("@creator"));
        rundeckToken.setToken(targetNode.valueOf("@token"));

        rundeckToken.setRoles(new HashSet<String>());
        List<Node> roles = targetNode.selectNodes("roles");
        for (Node role : roles) {
            String roleName = StringUtils.trimToNull(role.valueOf("role"));
            rundeckToken.getRoles().add(roleName);
        }


        return rundeckToken;
    }
}
