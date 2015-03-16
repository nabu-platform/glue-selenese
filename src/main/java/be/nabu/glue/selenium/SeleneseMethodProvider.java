package be.nabu.glue.selenium;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import be.nabu.glue.ScriptRuntime;
import be.nabu.glue.api.ExecutionContext;
import be.nabu.glue.api.MethodDescription;
import be.nabu.glue.api.MethodProvider;
import be.nabu.glue.api.ParameterDescription;
import be.nabu.glue.impl.SimpleMethodDescription;
import be.nabu.glue.impl.SimpleParameterDescription;
import be.nabu.glue.impl.methods.ScriptMethods;
import be.nabu.glue.impl.methods.TestMethods;
import be.nabu.libs.evaluator.EvaluationException;
import be.nabu.libs.evaluator.QueryPart;
import be.nabu.libs.evaluator.QueryPart.Type;
import be.nabu.libs.evaluator.api.Operation;
import be.nabu.libs.evaluator.api.OperationProvider.OperationType;
import be.nabu.libs.evaluator.base.BaseOperation;

public class SeleneseMethodProvider implements MethodProvider {
	
	@Override
	public Operation<ExecutionContext> resolve(String name) {
		if (name.equalsIgnoreCase("selenese")) {
			return new SeleneseOperation();
		}
		return null;
	}
	
	public static void transform(InputStream xml, InputStream xsl, OutputStream result) throws TransformerFactoryConfigurationError, TransformerException {
		Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(xsl));
		transformer.transform(new StreamSource(xml), new StreamResult(result));
	}
	
	public static class SeleneseOperation extends BaseOperation<ExecutionContext> {

		private long sleep = 10000, maxWait;
		
		private List<File> temporaryFiles = new ArrayList<File>();
		
		@Override
		public void finish() throws ParseException {
			// do nothing
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			// first the method name
			builder.append((String) getParts().get(0).getContent());
			// then the rest
			builder.append("(");
			for (int i = 1; i < getParts().size(); i++) {
				QueryPart part = getParts().get(i);
				if (i > 1) {
					builder.append(", ");
				}
				if (part.getType() == Type.STRING) {
					builder.append("\"" + part.getContent().toString() + "\"");
				}
				else {
					builder.append(part.getContent() == null ? "null" : part.getContent().toString());
				}
			}
			builder.append(")");
			return builder.toString();
		}
		
		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		public Object evaluate(ExecutionContext context) throws EvaluationException {
			List arguments = new ArrayList();
			for (int i = 1; i < getParts().size(); i++) {
				Operation<ExecutionContext> argumentOperation = (Operation<ExecutionContext>) getParts().get(i).getContent();
				arguments.add(argumentOperation.evaluate(context));
			}
			if (arguments.size() == 0) {
				arguments.add("testcase.html");
			}
			InputStream xml = null;
			if (arguments.get(0) instanceof String) {
				xml = find((String) arguments.get(0));
			}
			else if (arguments.get(0) instanceof byte[]) {
				xml = new ByteArrayInputStream((byte []) arguments.get(0));
			}
			else if (arguments.get(0) instanceof InputStream) {
				xml = (InputStream) arguments.get(0);
			}
			else {
				throw new EvaluationException("Can not process " + arguments.get(0));
			}
			try {
				SeleneseTestCase testCase = parse(xml, context);
				run(testCase, context);
			}
			catch (IOException e) {
				throw new EvaluationException(e);
			}
			for (File temporaryFile : temporaryFiles) {
				temporaryFile.delete();
			}
			return true;
		}
		
		protected WebDriver getFirefoxDriver() {
			DesiredCapabilities capabilities = DesiredCapabilities.firefox();
			FirefoxProfile profile = new FirefoxProfile();
			profile.setPreference("intl.accept_languages", "nl-BE, nl");
			capabilities.setCapability(FirefoxDriver.PROFILE, profile);
			return new FirefoxDriver(capabilities);
		}
		
		protected WebDriver getChromeDriver() {
			DesiredCapabilities capabilities = DesiredCapabilities.chrome();
			ChromeOptions co = new ChromeOptions();
			co.addArguments("?", "nl-BE, nl");
			capabilities.setCapability(ChromeOptions.CAPABILITY, co);
			return new FirefoxDriver(capabilities);
		}
		
		protected WebDriver getRemoteDriver(URL url) {
			// not sure what capabilities to give it, use a mix
			DesiredCapabilities capabilities = DesiredCapabilities.firefox();
			FirefoxProfile profile = new FirefoxProfile();
			profile.setPreference("intl.accept_languages", "nl-BE, nl");
			capabilities.setCapability(FirefoxDriver.PROFILE, profile);
			ChromeOptions co = new ChromeOptions();
			co.addArguments("?", "nl-BE, nl");
			capabilities.setCapability(ChromeOptions.CAPABILITY, co);
			return new RemoteWebDriver(url, capabilities);
		}
		
		@SuppressWarnings("unchecked")
		private void run(SeleneseTestCase testCase, ExecutionContext context) throws EvaluationException, IOException {
			String url = ScriptMethods.environment("selenium.server.url");
			maxWait = ScriptMethods.environment("selenium.server.timeout") == null ? 60000 : new Long(ScriptMethods.environment("selenium.server.url"));
			WebDriver driver = url == null ? getFirefoxDriver() : getRemoteDriver(new URL(url));
			String baseURL = testCase.getTarget().replaceAll("[/]+$", "");
			SeleneseStep previousStep = null;
			boolean closed = false;
			try {
				for (SeleneseStep step : testCase.getSteps()) {
					String message = step.getAction();
					if (step.getTarget() != null && !step.getTarget().isEmpty()) {
						message += " @ " + step.getTarget();
					}
					if (step.getContent() != null && !step.getContent().isEmpty()) {
						message += ": " + step.getContent();
					}
					if (ScriptRuntime.getRuntime().getExecutionContext().isDebug()) {
						ScriptRuntime.getRuntime().getFormatter().print(message);
					}
					if (step.getAction().equalsIgnoreCase("open")) {
						driver.get(step.getTarget().matches("^http[s]*://.*") ? step.getTarget() : baseURL + step.getTarget());
					}
					else if (step.getAction().equalsIgnoreCase("waitForTextPresent")) {
						Date date = new Date();
						while (!Thread.interrupted()) {
							if (driver.getPageSource().contains(step.getTarget())) {
								break;
							}
							else if (new Date().getTime() - date.getTime() > maxWait) {
								throw new EvaluationException("Timed out waiting for: " + step.getTarget());
							}
						}
					}
					else if (step.getAction().equalsIgnoreCase("waitForTextNotPresent")) {
						Date date = new Date();
						while (!Thread.interrupted()) {
							if (!driver.getPageSource().contains(step.getTarget())) {
								break;
							}
							else if (new Date().getTime() - date.getTime() > maxWait) {
								throw new EvaluationException("Timed out waiting for: " + step.getTarget());
							}
						}
					}
					else if (step.getAction().equalsIgnoreCase("verifyTextPresent") || step.getAction().equalsIgnoreCase("assertTextPresent")) {
						boolean fail = step.getAction().startsWith("assert");
						if (step.getTarget().contains("*")) {
							TestMethods.check("Verify presence of text", driver.getPageSource().matches(".*" + step.getTarget().replaceAll("\\*", ".*") + ".*"), step.getTarget(), fail);
						}
						else {
							TestMethods.check("Verify presence of text",  driver.getPageSource().contains(step.getTarget()), step.getTarget(), fail);
						}
					}
					else if ((step.getContent() == null || step.getContent().isEmpty()) && (step.getAction().equalsIgnoreCase("verifyTextNotPresent") || step.getAction().equalsIgnoreCase("assertTextNotPresent"))) {
						TestMethods.check("Verify absence of text", driver.getPageSource().contains(step.getTarget()), step.getTarget(), step.getAction().startsWith("assert"));
					}
					else if (step.getAction().equalsIgnoreCase("windowMaximize")) {
						driver.manage().window().maximize();
					}
					else if (step.getAction().equalsIgnoreCase("waitForPageToLoad")) {
						// TODO
					}
					else if (step.getAction().equalsIgnoreCase("waitForFrameToLoad")) {
						// TODO
					}
					else if (step.getAction().equalsIgnoreCase("setSpeed")) {
						this.sleep = new Long(step.getTarget()) * 1000;
					}
					else if (step.getAction().equalsIgnoreCase("close")) {
						driver.close();
						closed = true;
						break;
					}
					else if (step.getAction().equalsIgnoreCase("waitForPopUp")) {
						// do nothing
					}
					else if (step.getAction().equalsIgnoreCase("captureEntirePageScreenshot")) {
						String name = step.getTarget() != null && !step.getTarget().isEmpty() ? step.getTarget().replace('\\', '/').replaceAll(".*/", "") : UUID.randomUUID().toString();
						byte [] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
						// if you are debugging, write the screenshots to file
						if (ScriptRuntime.getRuntime().getExecutionContext().isDebug()) {
							File temporary = File.createTempFile("screenshot_" + name, ".png");
							FileOutputStream output = new FileOutputStream(temporary);
							try {
								output.write(screenshot);
								ScriptRuntime.getRuntime().getFormatter().print("Created screenshot " + temporary);
							}
							finally {
								output.close();
							}
						}
						if (!ScriptRuntime.getRuntime().getContext().containsKey("screenshots")) {
							ScriptRuntime.getRuntime().getContext().put("screenshots", new LinkedHashMap<String, byte[]>());
						}
						Map<String, byte[]> screenshots = (Map<String, byte[]>) ScriptRuntime.getRuntime().getContext().get("screenshots");
						screenshots.put(name.endsWith(".png") ? name : name + ".png", screenshot);
					}
					else if (step.getAction().equalsIgnoreCase("selectWindow")) {
						if (previousStep != null && previousStep.getAction().equalsIgnoreCase("waitForPopup")) {
							WebDriverWait wait = new WebDriverWait(driver, this.sleep / 1000);
							wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(step.getTarget()));
						}
						else {
							driver.switchTo().window(step.getTarget().equals("null") ? null : step.getTarget());
						}
					}
					else if (step.getAction().equalsIgnoreCase("assertConfirmation")) {
						Alert alert = driver.switchTo().alert();
						if (alert.getText().equals(step.getTarget())) {
							alert.accept();
						}
					}
					else {
						By by = null;
						if (step.getTarget().matches("^[\\w]+=.*")) {
							int index = step.getTarget().indexOf('=');
							String selector = step.getTarget().substring(0, index).trim();
							String value = step.getTarget().substring(index + 1).trim();
							if (selector.equalsIgnoreCase("id")) {
								by = By.id(value);
							}
							else if (selector.equalsIgnoreCase("css")) {
								by = By.cssSelector(value);
							}
							else if (selector.equalsIgnoreCase("link")) {
								by = By.linkText(value);
							}
							else if (selector.equalsIgnoreCase("xpath")) {
								by = By.xpath(value);
							}
							// TODO: the "class" selector is a guess, i haven't actually seen it in action yet
							else if (selector.equalsIgnoreCase("class")) {
								by = By.className(value);
							}
							// TODO
							else if (selector.equalsIgnoreCase("tag")) {
								by = By.tagName(value);
							}
							// TODO
							else if (selector.equalsIgnoreCase("name")) {
								by = By.name(value);
							}
							else {
								throw new EvaluationException("Unknown selector type: " + selector);
							}
						}
						else {
							by = By.xpath(step.getTarget());
						}
						WebDriverWait wait = new WebDriverWait(driver, this.sleep / 1000);
						wait.until(ExpectedConditions.presenceOfElementLocated(by));
						
						WebElement element = driver.findElement(by);
						// you are requesting a file upload
						if (element == null) {
							throw new EvaluationException("Can not find element " + by);
						}
						if (step.getAction().equalsIgnoreCase("type")) {
							if (element.getTagName().equalsIgnoreCase("input") && element.getAttribute("type").equalsIgnoreCase("file")) {
								String fileName = step.getContent().replaceAll(".*[\\\\/]+([^\\\\/]+)$", "$1");
								InputStream content = find(fileName);
								if (content == null) {
									throw new FileNotFoundException("Could not find file " + fileName + " for upload");
								}
								try {
									File file = File.createTempFile(fileName.replaceAll("\\.[^.]+", ""), fileName.replaceAll(".*\\.([^.]+)", "$1"));
									temporaryFiles.add(file);
									FileOutputStream output = new FileOutputStream(file);
									try {
										int read = 0;
										byte [] buffer = new byte[4096];
										while ((read = content.read(buffer)) != -1) {
											output.write(buffer, 0, read);
										}
										element.sendKeys(file.getAbsolutePath());
									}
									finally {
										output.close();
									}
								}
								finally {
									content.close();
								}
							}
							else {
								element.sendKeys(step.getContent());
							}
						}
						else if (step.getAction().equalsIgnoreCase("store")) {
							context.getPipeline().put(step.getContent(), element.getText());
						}
						else if (step.getAction().equalsIgnoreCase("doubleClick")) {
							Actions action = new Actions(driver);
							// there is apparently a bug where you have to send this before the double click will work: http://stackoverflow.com/questions/25756876/selenium-webdriver-double-click-doesnt-work
							driver.findElement(by).sendKeys("");
							action.doubleClick(element).perform();
						}
						else if (step.getAction().equalsIgnoreCase("waitForElementPresent") || step.getAction().equalsIgnoreCase("verifyElementPresent")) {
							// we already waited for the element, so keep going
						}
						else if (step.getAction().equalsIgnoreCase("verifyText") || step.getAction().equalsIgnoreCase("assertText")) {
							boolean fail = step.getAction().startsWith("assert");
							if (step.getTarget().contains("*")) {
								boolean matches = element.getText().matches(".*" + step.getContent().replaceAll("\\*", ".*") + ".*");
								TestMethods.check("Verify presence of text in " + step.getTarget(), matches, matches ? step.getContent() : element.getText() + " !~ " + step.getContent(), fail);
							}
							else {
								boolean contains = element.getText().contains(step.getContent());
								TestMethods.check("Verify presence of text in " + step.getTarget(), contains, contains ? step.getContent() : element.getText() + " !# " + step.getContent(), fail);
							}
						}
						else if ((step.getContent() == null || step.getContent().isEmpty()) && (step.getAction().equalsIgnoreCase("verifyTextNotPresent") || step.getAction().equalsIgnoreCase("assertTextNotPresent"))) {
							TestMethods.check("Verify absence of text in " + step.getTarget(), element.getText().contains(step.getContent()), step.getContent(), step.getAction().startsWith("assert"));
						}
						else if (step.getAction().equalsIgnoreCase("clickAndWait")) {
							element.click();
							try {
								Thread.sleep(step.getContent() == null || step.getContent().isEmpty() ? this.sleep : new Long(step.getContent()));
							}
							catch (InterruptedException e) {
								// continue
							}
						}
						else if (step.getAction().equalsIgnoreCase("click") || (step.getAction().equalsIgnoreCase("clickAt") && (step.getContent() == null || step.getContent().trim().isEmpty()))) {
							element.click();
						}
						else if (step.getAction().equalsIgnoreCase("clickAt")) {
							Actions action = new Actions(driver);
							String [] parts = step.getContent().split(",");
							action.moveToElement(element, new Integer(parts[0]), parts.length > 1 ? new Integer(parts[1]) : 0).click().perform();
						}
					}
					previousStep = step;
				}
			}
			finally {
				if (!closed) {
					driver.close();
				}
			}
		}

		private InputStream find(String name) throws EvaluationException {
			ScriptRuntime runtime = ScriptRuntime.getRuntime();
			InputStream xml = null;
			try {
				while (runtime != null) {
					xml = ScriptRuntime.getRuntime().getExecutionContext().getContent(name);
					if (xml != null) {
						break;
					}
					runtime = runtime.getParent();
				}
			}
			catch (IOException e) {
				throw new EvaluationException(e);
			}
			if (xml == null) {
				throw new EvaluationException("Can not find the xml " + name);
			}
			return xml;
		}
		
		private SeleneseTestCase parse(InputStream xml, ExecutionContext context) throws EvaluationException {
			try {
				InputStream xsl = getClass().getClassLoader().getResourceAsStream("selenese2xml.xslt");
				if (xsl == null) {
					throw new RuntimeException("Can not find the file selenese2xml.xslt");
				}
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				transform(xml, xsl, output);
				Charset charset = ScriptRuntime.getRuntime().getScript().getCharset();
				String string = new String(output.toByteArray(), charset);
				string = ScriptRuntime.getRuntime().getScript().getParser().substitute(string, context, false);
				JAXBContext jaxb = JAXBContext.newInstance(SeleneseTestCase.class);
				return (SeleneseTestCase) jaxb.createUnmarshaller().unmarshal(new StreamSource(new ByteArrayInputStream(string.getBytes(charset))));
			}
			catch (TransformerFactoryConfigurationError e) {
				throw new EvaluationException(e);
			}
			catch (TransformerException e) {
				throw new EvaluationException(e);
			}
			catch (JAXBException e) {
				throw new EvaluationException(e);
			}
		}

		@Override
		public OperationType getType() {
			return OperationType.METHOD;
		}
	}
	
	@XmlRootElement(name = "testCase", namespace = "http://nabu.be/glue/selenese")
	public static class SeleneseTestCase {
		private List<SeleneseStep> steps;
		private String target;

		@XmlElement(name = "step", namespace = "http://nabu.be/glue/selenese")
		public List<SeleneseStep> getSteps() {
			return steps;
		}

		public void setSteps(List<SeleneseStep> steps) {
			this.steps = steps;
		}

		@XmlAttribute
		public String getTarget() {
			return target;
		}
		public void setTarget(String target) {
			this.target = target;
		}
	}
	
	public static class SeleneseStep {
		private String action, target, content;

		@XmlAttribute
		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		@XmlAttribute
		public String getTarget() {
			return target;
		}

		public void setTarget(String target) {
			this.target = target;
		}

		@XmlValue
		public String getContent() {
			return content;
		}

		public void setContent(String content) {
			this.content = content;
		}
		
	}

	@Override
	public List<MethodDescription> getAvailableMethods() {
		List<MethodDescription> descriptions = new ArrayList<MethodDescription>();
		descriptions.add(new SimpleMethodDescription("Selenium", "selenese", "This will run a selenese script created using the selenium IDE",
			Arrays.asList(new ParameterDescription [] { new SimpleParameterDescription("script", "You can pass in the name of the file that holds the selense or alternatively you can pass in byte[] or InputStream", "String, byte[], InputStream") } ),
			new ArrayList<ParameterDescription>()));
		return descriptions;
	}
}
