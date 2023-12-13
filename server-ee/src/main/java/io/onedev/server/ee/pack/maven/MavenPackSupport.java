package io.onedev.server.ee.pack.maven;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.PackManager;
import io.onedev.server.model.Pack;
import io.onedev.server.model.Project;
import io.onedev.server.pack.PackSupport;
import org.apache.wicket.Component;
import org.apache.wicket.model.LoadableDetachableModel;

public class MavenPackSupport implements PackSupport {

	public static final String TYPE = "Maven";
	
	@Override
	public int getOrder() {
		return 200;
	}

	@Override
	public String getPackType() {
		return TYPE;
	}

	@Override
	public String getPackIcon() {
		return "maven";
	}

	@Override
	public String getProjectSeparator() {
		return ">";
	}

	@Override
	public String getReference(Pack pack) {
		if (pack.getArtifactId() != null && pack.getVersion() != null)
			return pack.getGAV();
		else 
			return pack.getGroupId() + ":<Plugins Metadata>";
	}

	@Override
	public Component renderContent(String componentId, Pack pack) {
		var packId = pack.getId();
		return new MavenPackPanel(componentId, new LoadableDetachableModel<>() {
			@Override
			protected Pack load() {
				return OneDev.getInstance(PackManager.class).load(packId);
			}
			
		});
	}

	@Override
	public Component renderHelp(String componentId, Project project) {
		return new MavenHelpPanel(componentId, project.getPath());
	}
	
}
