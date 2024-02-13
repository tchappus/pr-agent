package com.tjc.pragent;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@SpringBootApplication
public class PragentApplication {

	public static void main(String[] args) {
		SpringApplication.run(PragentApplication.class, args);
	}

}

@RestController
@RequiredArgsConstructor
class PrAgentController {

	private final GithubService githubService;
	private final PromptService promptService;
	private final OpenAiService openAiService;

	@GetMapping("/test")
	public ResponseEntity<Map<String, Object>> test() {
		return ResponseEntity.ok(githubService.getPullRequest());
	}

	@GetMapping("/testfiles")
	public ResponseEntity<List<Map<String, Object>>> testfiles() {
		return ResponseEntity.ok(githubService.getPullRequestFiles());
	}

	@GetMapping("/dothing")
	public ResponseEntity<String> doThing() {
		var prompt = promptService.getPrompt();
		var aiResponse = openAiService.performRequest(prompt);
		githubService.postPullRequestComment(aiResponse);
		return ResponseEntity.ok("");
	}

	@GetMapping("/template")
	public ResponseEntity<String> template() {
		return ResponseEntity.ok(promptService.getPrompt().getLeft() + promptService.getPrompt().getRight());
	}
}

@Component
class GithubService {
	private final String githubToken;

	public GithubService(@Value("${GITHUB-API-KEY}") String githubToken) {
		this.githubToken = githubToken;
	}

	public Map<String, Object> getPullRequest() {
		return RestClient.create()
				.get()
				.uri("https://api.github.com/repos/{owner}/{repo}/pulls/{prNumber}", "tchappus", "demo-service", "3")
				.header("Authorization", "Bearer %s".formatted(githubToken))
				.header("Accept", "application/vnd.github+json")
				.retrieve()
				.body(new ParameterizedTypeReference<>() {});
	}

	public List<Map<String, Object>> getPullRequestFiles() {
		return RestClient.create()
				.get()
				.uri(new DefaultUriBuilderFactory()
						.uriString("https://api.github.com/repos/{owner}/{repo}/pulls/{prNumber}/files")
						.queryParam("per_page", 30)
						.queryParam("page", 1)
						.build("tchappus", "demo-service", "3"))
				.header("Authorization", "Bearer %s".formatted(githubToken))
				.header("Accept", "application/vnd.github+json")
				.retrieve()
				.body(new ParameterizedTypeReference<>() {});
	}

	public void postPullRequestComment(Map<String, Object> openAiResponse) {
		var prAnalysis = (Map<String, String>) openAiResponse.get("PR Analysis");
		var mainTheme = prAnalysis.get("Main theme");
		var prSummary = prAnalysis.get("PR summary");
		var prType = prAnalysis.get("Type of PR");
		var estimatedEffortToReview = prAnalysis.get("Estimated effort to review [1-5]");

		var prFeedback = (Map<String, Object>) openAiResponse.get("PR Feedback");
		var generalSuggestions = prFeedback.get("General suggestions");
		var codeFeedbackList = (List<Map<String, String>>) prFeedback.get("Code feedback");
		var codeFeedback = new StringBuilder();
		for (var s : codeFeedbackList) {
			codeFeedback.append("""
					  * **relevant file:** `%s`
					
					    **relevant line:** `%s`
					
					    **suggestion:** %s
					
					""".formatted(s.get("relevant file"), s.get("relevant line"), s.get("suggestion")));
		}

		RestClient.create()
				.post()
				.uri("https://api.github.com/repos/{owner}/{repo}/pulls/{prNumber}/reviews", "tchappus", "demo-service", "3")
				.header("Authorization", "Bearer %s".formatted(githubToken))
				.header("Accept", "application/vnd.github+json")
				.body(Map.of(
						"event", "COMMENT",
						"body", """
								## PR Analysis ✨
								
								* 🎯  **Main theme:** %s
								
								* 📝  **PR summary:** %s
								
								* 📌  **Type of PR:** %s
								
								* ⏱️  **Estimated effort to review [1-5]:** %s
								
								## PR Feedback 🧐
								
								* 💡  **General suggestions:** %s
								
								* 🤖  **Code feedback:**
								%s
								"""
								.formatted(mainTheme, prSummary, prType, estimatedEffortToReview, generalSuggestions, codeFeedback)
				))
				.retrieve();
	}
}

@Component
@RequiredArgsConstructor
class PromptService {
	private static final Set<String> PERMITTED_FILE_EXTENSIONS = Set.of("java");
	private static final Set<String> PERMITTED_FILE_STATUSES = Set.of("added", "modified", "changed");

	private static final List<Function<String, Optional<String>>> DEFAULT_LINE_PROCESSORS = List.of(
			l -> Optional.of(l.trim()),		// trim line
			l -> l.isBlank() ? Optional.empty() : Optional.of(l),	// omit blanks
			l -> l.startsWith("import") || l.startsWith("+import") || l.startsWith("-import") ?  // omit import statements
					Optional.empty() :
					Optional.of(l)
	);

	private final Configuration freemarkerCfg;
	private final GithubService githubService;

	public Pair<String, String> getPrompt() {
		var pr = githubService.getPullRequest();
		var prFiles = githubService.getPullRequestFiles();
		return getPrompt(pr, prFiles, DEFAULT_LINE_PROCESSORS);
	}

	public Pair<String, String> getPrompt(Map<String, Object> pr,
										  List<Map<String, Object>> prFiles,
										  List<Function<String, Optional<String>>> patchLineProcessors) {
        try {
			var diff = new StringBuilder();
			for (var f : prFiles) {
				var fileName = (String) f.get("filename");
				var fileSplit = fileName.split("\\.");
				if (fileSplit.length == 0 || !PERMITTED_FILE_EXTENSIONS.contains(fileSplit[fileSplit.length-1])) {
					continue;
				}
				if (!PERMITTED_FILE_STATUSES.contains(f.get("status").toString())) {
					continue;
				}
				var patch = f.get("patch").toString();
				patch = patch
						.replaceAll(" {2,}", "")  // remove extra whitespace
						.replaceAll("\n ", "\n"); // remove whitespace after newlines

				var editedPatch = new StringBuilder();
				var lines = 0;
				for (var l : patch.split("\n")) {
					for (var p : patchLineProcessors) {
						var processed = p.apply(l);
						if (processed.isPresent()) {
							l = processed.get();
						}
					}
					editedPatch.append(l).append("\n");
					lines++;
				}
				if (lines <= 1) {
					continue;
				}

				diff.append("## %s \n\n".formatted(fileName));
				diff.append(editedPatch);
				diff.append("...\n\n\n");
			}

            var systemTmpl = freemarkerCfg.getTemplate("prompt_templates/pr_review_template_system.ftl");

			var writer = new StringWriter();
			systemTmpl.process(Map.of(
					"numCodeSuggestions", 3,
					"numCodeSuggestionsGreaterThanZero", true,
					"requireEstimatedEffortToReview", true
			), writer);

			var systemPrompt = writer.toString();

			var userTmpl = freemarkerCfg.getTemplate("prompt_templates/pr_review_template_user.ftl");
			writer = new StringWriter();
			userTmpl.process(Map.of(
					"title", pr.get("title"),
					"branch", ((Map<String, String>)pr.get("head")).get("ref"),
					"language", "Java",
					"diff", diff.toString()
			), writer);

			var userPrompt = writer.toString();
			return Pair.of(systemPrompt, userPrompt);
        } catch (IOException | TemplateException e) {
            throw new RuntimeException(e);
        }
    }
}

@Component
class OpenAiService {
	private final String openAiToken;

	public OpenAiService(@Value("${OPEN-AI-API-KEY}") String openAiToken) {
		this.openAiToken = openAiToken;
	}

	public Map<String, Object> performRequest(Pair<String, String> prompt) {

		var resp = RestClient.create()
				.post()
				.uri("https://api.openai.com/v1/chat/completions")
				.header("Authorization", "Bearer %s".formatted(openAiToken))
				.body(Map.of(
						"model", "gpt-4",
						"messages", List.of(
								Map.of("role", "system",
										"content", prompt.getLeft()),
								Map.of("role", "user",
										"content", prompt.getRight())
						)
				))
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {});

		var choice = ((List<Map<String, Object>>) resp.get("choices")).get(0);
		var content = ((Map<String, String>) choice.get("message")).get("content");
		System.out.println(content);
		var yaml = new Yaml();
		return yaml.load(content);

//		var sampleResponse = """
//				PR Analysis:
//				  Main theme: |-
//				    Update to the Repo Mapper
//				  PR summary: |-
//				    This PR changes the GithubMapper in the application to map repos to their 'number' field instead of the 'name' field. Also, it updates the GithubDownstreamService's getAllRepos method to take extra argument 'foo'.
//				  Type of PR: |-
//				    Refactoring
//				  Estimated effort to review [1-5]: |-
//				    2, because the changes are relatively small, but understanding the context of the changes may require a moderate amount of effort
//				PR Feedback:
//				  General suggestions: |-
//				    Firstly, please clarify what the 'foo' parameter is meant for, as its purpose or usage isn't clear from the changes made in the PR. Secondly, it'd be helpful to understand the motivation behind changing from 'name' to 'number' for mapping the repos. Is it to cater to any specific requirement? Without more context, it's difficult to give definitive feedback. Please provide relevant details.
//				  Code feedback:
//				    - relevant file: |-
//				        src/main/java/com/example/demo/GithubDownstreamService.java
//				      suggestion: |-
//				        Naming variables and method parameters properly is an important best practice. Please consider giving the 'foo' parameter a more meaningful name that reflects what it's used for in the logic. [important]
//				      relevant line: |-
//				        +public Flux<Map<String, Object>> getAllRepos(String user, String foo) {
//				    - relevant file: |-
//				        src/main/java/com/example/demo/GithubMapper.java
//				      suggestion: |-
//				        It might lead to runtime exceptions if the 'number' field does not exist or is not a String in the 'repo' Map. Therefore, it would be safer to check if the key exists and the corresponding value is an instance of String before casting it. [important]
//				      relevant line: |-
//				        +return (String)repo.get("number");
//				    - relevant file: |-
//				        src/main/java/com/example/demo/GithubService.java
//				      suggestion: |-
//				        If the 'foo' parameter is required for the 'getAllRepos' method, it would be better to make its usage obvious instead of passing a hard-coded empty string. Provide the param in the 'getReposForUser' method or find a way to generate it dynamically. [medium]
//				      relevant line: |-
//				        +return githubDownstreamService.getAllRepos(username, "")
//				""";
//
//		var yaml = new Yaml();
//		return yaml.load(sampleResponse);
	}
}