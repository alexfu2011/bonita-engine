package com.bonitasoft.engine.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bonitasoft.engine.expression.exception.SExpressionEvaluationException;
import org.bonitasoft.engine.expression.model.SExpression;
import org.bonitasoft.engine.expression.model.impl.SExpressionImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.bonitasoft.engine.bdm.Entity;
import com.bonitasoft.engine.business.data.BusinessDataRepository;
import com.bonitasoft.engine.business.data.NonUniqueResultException;

@RunWith(MockitoJUnitRunner.class)
public class QueryBusinessDataExpressionExecutorStrategyTest {

    @Mock
    private BusinessDataRepository businessDataRepository;

    @InjectMocks
    private QueryBusinessDataExpressionExecutorStrategy strategy;

    private Map<String, Object> buildContext() {
        final Map<String, Object> context = new HashMap<String, Object>();
        context.put("containerId", 784654l);
        context.put("containerType", "activity");
        return context;
    }

    @Test
    public void evaluation_result_should_not__be_pushed_in_context() throws Exception {
        assertThat(strategy.mustPutEvaluatedExpressionInContext()).isFalse();
    }

    @Test
    public void evaluate_should_return_a_long_result_after_querying() throws Exception {
        final SExpressionImpl expression = new SExpressionImpl("employees", "countEmployees", null, Long.class.getName(), null, null);
        final Long result = Long.valueOf(45);
        when(businessDataRepository.findByNamedQuery("countEmployees", Long.class, Collections.<String, Serializable> emptyMap())).thenReturn(result);

        final Long count = (Long) strategy.evaluate(expression, buildContext(), null);

        assertThat(count).isEqualTo(result);
    }

    @Test
    public void evaluate_should_return_the_list_of_results_after_querying() throws Exception {
        final SExpressionImpl expression = new SExpressionImpl("employees", "getEmployees", null, List.class.getName(), null, null);

        strategy.evaluate(expression, buildContext(), null);

        verify(businessDataRepository).findListByNamedQuery("getEmployees", Entity.class, Collections.<String, Serializable> emptyMap());
    }

    @Test
    public void evaluate_should_return_a_result_after_querying_with_parameters() throws Exception {
        final SExpressionImpl dependencyFirstNameExpression = new SExpressionImpl("firstName", "John", null, String.class.getName(), null, null);
        final SExpressionImpl dependencyLastNameExpression = new SExpressionImpl("lastName", "Doe", null, String.class.getName(), null, null);
        final List<SExpression> dependencies = new ArrayList<SExpression>();
        dependencies.add(dependencyFirstNameExpression);
        dependencies.add(dependencyLastNameExpression);
        final SExpressionImpl expression = new SExpressionImpl("employees", "getEmployeeByFirstNameAndLastName", "", "com.bonitasoft.Employee", "",
                dependencies);
        final Map<Integer, Object> resolvedExpressions = new HashMap<Integer, Object>();
        resolvedExpressions.put(dependencyFirstNameExpression.getDiscriminant(), "John");
        resolvedExpressions.put(dependencyLastNameExpression.getDiscriminant(), "Doe");

        strategy.evaluate(expression, buildContext(), resolvedExpressions);

        final Map<String, Serializable> parameters = new HashMap<String, Serializable>();
        parameters.put("firstName", "John");
        parameters.put("lastName", "Doe");
        verify(businessDataRepository).findByNamedQuery("getEmployeeByFirstNameAndLastName", Entity.class, parameters);
    }

    @Test(expected = SExpressionEvaluationException.class)
    public void evaluate_should_throw_an_exception_after_querying() throws Exception {
        final SExpressionImpl expression = new SExpressionImpl("employees", "getEmployees", null, Entity.class.getName(), null, null);
        when(businessDataRepository.findByNamedQuery("getEmployees", Entity.class, Collections.<String, Serializable> emptyMap())).thenThrow(
                new NonUniqueResultException(new IllegalArgumentException("several")));

        strategy.evaluate(expression, buildContext(), null);
    }

}
