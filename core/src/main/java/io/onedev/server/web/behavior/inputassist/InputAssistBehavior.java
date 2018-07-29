package io.onedev.server.web.behavior.inputassist;

import static org.apache.wicket.ajax.attributes.CallbackParameter.explicit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxChannel;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.request.IRequestParameters;
import org.apache.wicket.request.cycle.RequestCycle;
import org.unbescape.javascript.JavaScriptEscape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

import io.onedev.codeassist.InputCompletion;
import io.onedev.codeassist.InputStatus;
import io.onedev.launcher.loader.AppLoader;
import io.onedev.server.web.behavior.AbstractPostAjaxBehavior;
import io.onedev.server.web.component.floating.AlignPlacement;
import io.onedev.server.web.component.floating.AlignTarget;
import io.onedev.server.web.component.floating.Alignment;
import io.onedev.server.web.component.floating.ComponentTarget;
import io.onedev.server.web.component.floating.FloatingPanel;
import io.onedev.utils.Range;
import io.onedev.utils.RangeUtils;

@SuppressWarnings("serial")
public abstract class InputAssistBehavior extends AbstractPostAjaxBehavior {

	public static final int MAX_SUGGESTIONS = 1000;
	
	private FloatingPanel dropdown;
	
	@Override
	protected void onBind() {
		super.onBind();
		
		Component inputField = getComponent();
		inputField.setOutputMarkupId(true);
	}

	private int getLine(String content, int charIndex) {
		int line = 0;
		if (charIndex >= content.length())
			charIndex = content.length()-1;
		for (int i=0; i<=charIndex; i++) {
			if (content.charAt(i) == '\n')
				line++;
		}
		return line;
	}
	
	@Override
	protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
		super.updateAjaxAttributes(attributes);
		attributes.setChannel(new AjaxChannel("input-assist", AjaxChannel.Type.DROP));
	}

	private List<Range> normalizeErrors(String inputContent, List<Range> errors) {
		List<Range> normalizedErrors = new ArrayList<>();
		
		List<String> lines = Splitter.on('\n').splitToList(inputContent);
		for (Range error: RangeUtils.merge(errors)) {
			int fromLine = getLine(inputContent, error.getFrom());
			int toLine = getLine(inputContent, error.getTo());
			if (fromLine != toLine) {
				int index = getCharIndex(inputContent, fromLine, lines.get(fromLine).length()-1);
				if (index >= error.getFrom())
					normalizedErrors.add(new Range(error.getFrom(), index));
				index = getCharIndex(inputContent, toLine, 0);
				if (index <= error.getTo())
					normalizedErrors.add(new Range(index, error.getTo()));
				for (int i=fromLine+1; i<toLine; i++) {
					String line = lines.get(i);
					if (line.length() != 0) {
						int from = getCharIndex(inputContent, i, 0);
						normalizedErrors.add(new Range(from, from+line.length()-1));
					}
				}
			} else {
				normalizedErrors.add(error);
			}
		}

		return normalizedErrors;
	}
	
	protected int getCharIndex(String content, int line, int charIndexInLine) {
		List<String> lines = Splitter.on('\n').splitToList(content);
		int index = 0;
		for (int i=0; i<line; i++)
			index += lines.get(i).length()+1;
		return index + charIndexInLine;
	}		

	@Override
	protected void respond(AjaxRequestTarget target) {
		IRequestParameters params = RequestCycle.get().getRequest().getPostParameters();
		String type = params.getParameterValue("type").toString();
		if (type.equals("close")) {
			if (dropdown != null)
				dropdown.close();
		} else {
			String inputContent = params.getParameterValue("input").toString();
			Integer inputCaret = params.getParameterValue("caret").toOptionalInteger();

			Preconditions.checkArgument(inputContent.indexOf('\r') == -1);
			
			List<Range> errors = getErrors(inputContent);
			if (errors == null)
				errors = new ArrayList<>();
			List<Range> normalizedErrors = normalizeErrors(inputContent, errors);
			String json;
			try {
				json = AppLoader.getInstance(ObjectMapper.class).writeValueAsString(normalizedErrors);
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
			String script = String.format("onedev.server.inputassist.markErrors('%s', %s);", 
					getComponent().getMarkupId(), json);
			target.appendJavaScript(script);
			
			if (inputCaret != null) {
				InputStatus inputStatus = new InputStatus(inputContent, inputCaret);
				List<InputCompletion> suggestions = getSuggestions(new InputStatus(inputContent, inputCaret));
				int count = 0;
				for (Iterator<InputCompletion> it = suggestions.iterator(); it.hasNext();) {
					it.next();
					if (++count > MAX_SUGGESTIONS)
						it.remove();
				}
				if (!suggestions.isEmpty()) {
					int anchor = getAnchor(inputContent.substring(0, inputCaret));
					if (dropdown == null) {
						dropdown = new FloatingPanel(target, new Alignment(new ComponentTarget(getComponent(), anchor), AlignPlacement.bottom(0))) {

							@Override
							protected Component newContent(String id) {
								return new AssistPanel(id, getComponent(), inputStatus, suggestions, getHints(inputStatus));
							}

							@Override
							protected void onClosed() {
								super.onClosed();
								dropdown = null;
							}
							
						};
						script = String.format("onedev.server.inputassist.assistOpened('%s', '%s', '%s');", 
								getComponent().getMarkupId(), dropdown.getMarkupId(), JavaScriptEscape.escapeJavaScript(inputContent));
						target.appendJavaScript(script);
					} else {
						Component content = dropdown.getContent();
						Component newContent = new AssistPanel(content.getId(), getComponent(), inputStatus, suggestions, getHints(inputStatus));
						content.replaceWith(newContent);
						target.add(newContent);

						AlignTarget alignTarget = new ComponentTarget(getComponent(), anchor);
						script = String.format("$('#%s').data('alignment').target=%s;", dropdown.getMarkupId(), alignTarget);
						target.prependJavaScript(script);
						
						script = String.format("onedev.server.inputassist.assistUpdated('%s', '%s', '%s');", 
								getComponent().getMarkupId(), dropdown.getMarkupId(), JavaScriptEscape.escapeJavaScript(inputContent));
						target.appendJavaScript(script);
					}
				} else if (dropdown != null) {
					dropdown.close();
				}
			} else if (dropdown != null) {
				dropdown.close();
			}
		}
	}
	
	public void close() {
		if (dropdown != null)
			dropdown.close();
	}
	
	@Override
	public void renderHead(Component component, IHeaderResponse response) {
		super.renderHead(component, response);

		response.render(JavaScriptHeaderItem.forReference(new InputAssistResourceReference()));
		
		String script = String.format("onedev.server.inputassist.init('%s', %s);", 
				getComponent().getMarkupId(true), 
				getCallbackFunction(explicit("type"), explicit("input"), explicit("caret")));
		
		response.render(OnDomReadyHeaderItem.forScript(script));
	}

	protected abstract List<InputCompletion> getSuggestions(InputStatus inputStatus);

	protected List<String> getHints(InputStatus inputStatus) {
		return new ArrayList<>();
	}
	
	protected abstract List<Range> getErrors(String inputContent);
	
	/**
	 * Given an input content, anchor is index of the char at which place to display left side 
	 * of the suggestion window. This makes it possible to make the suggestion window moves 
	 * as one types, instead of always staying at a fixed place  
	 *  
	 * @param inputContent
	 * @return
	 */
	protected abstract int getAnchor(String inputContent);
	
}