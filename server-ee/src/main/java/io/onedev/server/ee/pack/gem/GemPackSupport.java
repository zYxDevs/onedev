package io.onedev.server.ee.pack.gem;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.PackManager;
import io.onedev.server.model.Pack;
import io.onedev.server.model.Project;
import io.onedev.server.pack.PackSupport;
import org.apache.wicket.Component;
import org.apache.wicket.model.LoadableDetachableModel;

public class GemPackSupport implements PackSupport {
	
	public static final String TYPE = "RubyGems";
	
	@Override
	public int getOrder() {
		return 400;
	}

	@Override
	public String getPackType() {
		return TYPE;
	}

	@Override
	public String getPackIcon() {
		return "ruby";
	}

	@Override
	public String getProjectSeparator() {
		return ":";
	}

	@Override
	public String getReference(Pack pack) {
		return pack.getName() + "-" + pack.getVersion();
	}

	@Override
	public Component renderContent(String componentId, Pack pack) {
		var packId = pack.getId();
		return new GemPackPanel(componentId, new LoadableDetachableModel<>() {
			@Override
			protected Pack load() {
				return OneDev.getInstance(PackManager.class).load(packId);
			}

		});
	}

	@Override
	public Component renderHelp(String componentId, Project project) {
		return new GemHelpPanel(componentId, project.getPath());
	}
	
}
