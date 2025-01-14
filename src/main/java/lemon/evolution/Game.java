package lemon.evolution;

import lemon.engine.toolbox.TaskQueue;
import lemon.engine.control.GLFWWindow;
import lemon.engine.control.Loader;
import lemon.engine.draw.CommonDrawables;
import lemon.engine.draw.Drawable;
import lemon.engine.frameBuffer.FrameBuffer;
import lemon.engine.function.MurmurHash;
import lemon.engine.function.PerlinNoise;
import lemon.engine.function.SzudzikIntPair;
import lemon.engine.game.Player;
import lemon.engine.input.CursorPositionEvent;
import lemon.engine.input.MouseScrollEvent;
import lemon.engine.math.Box2D;
import lemon.engine.math.MathUtil;
import lemon.engine.math.Matrix;
import lemon.engine.math.MutableVector3D;
import lemon.engine.math.Projection;
import lemon.engine.math.Vector2D;
import lemon.engine.math.Vector3D;
import lemon.engine.model.LineGraph;
import lemon.engine.render.MatrixType;
import lemon.engine.texture.Texture;
import lemon.engine.texture.TextureBank;
import lemon.engine.texture.TextureData;
import lemon.engine.time.Benchmarker;
import lemon.engine.toolbox.Color;
import lemon.engine.toolbox.Disposables;
import lemon.engine.toolbox.Histogram;
import lemon.engine.toolbox.ObjLoader;
import lemon.engine.toolbox.SkyboxLoader;
import lemon.engine.toolbox.Toolbox;
import lemon.evolution.destructible.beta.ScalarField;
import lemon.evolution.destructible.beta.Terrain;
import lemon.evolution.destructible.beta.TerrainChunk;
import lemon.evolution.destructible.beta.TerrainGenerator;
import lemon.evolution.entity.RocketLauncherProjectile;
import lemon.evolution.physics.beta.CollisionContext;
import lemon.evolution.physics.beta.CollisionPacket;
import lemon.evolution.physics.beta.CollisionResponse;
import lemon.evolution.pool.MatrixPool;
import lemon.evolution.entity.PuzzleBall;
import lemon.evolution.screen.beta.Screen;
import lemon.evolution.setup.CommonProgramsSetup;
import lemon.evolution.ui.beta.UIScreen;
import lemon.evolution.util.BasicControlActivator;
import lemon.evolution.util.CommonPrograms2D;
import lemon.evolution.util.CommonPrograms3D;
import lemon.evolution.util.PlayerControl;
import lemon.evolution.util.ShaderProgramHolder;
import lemon.evolution.world.Location;
import lemon.evolution.world.World;
import lemon.evolution.world.WorldRenderer;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Game implements Screen {
	private static final Logger logger = Logger.getLogger(Game.class.getName());

	private GLFWWindow window;
	private boolean loaded;

	private Player player;
	private CollisionContext collisionContext;

	private FrameBuffer frameBuffer;

	private DebugOverlay debugOverlay;
	private Benchmarker benchmarker;

	private World world;
	private WorldRenderer worldRenderer;

	private Drawable dragonModel;
	private Vector3D lightPosition;

	private Drawable rocketLauncherUnloadedModel;
	private Drawable rocketLauncherLoadedModel;
	private Drawable rocketLauncherProjectileModel;

	private TaskQueue postLoadTasks = TaskQueue.ofConcurrent();

	public List<Vector3D> debug;

	private UIScreen uiScreen;

	private ThreadPoolExecutor pool;
	private ThreadPoolExecutor pool2;

	private Histogram histogram;

	private final Disposables disposables = new Disposables();

	private ScalarField scalarField;

	public Game(ScalarField scalarField){
		this.scalarField = scalarField;
	}

	public void onLoad(GLFWWindow window) {
		if(!loaded){
			pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(3);
			pool2 = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
			pool.setRejectedExecutionHandler((runnable, executor) -> {});
			pool2.setRejectedExecutionHandler((runnable, executor) -> {});
			disposables.add(() -> pool.shutdown());
			disposables.add(() -> pool2.shutdown());
			TerrainGenerator generator = new TerrainGenerator(pool, scalarField);
			var terrain = new Terrain(generator, pool2, Vector3D.of(5f, 5f, 5f));
			collisionContext = (position, velocity, collision) -> {
				var after = position.add(velocity);
				int minChunkX = terrain.getChunkX(Math.min(position.x(), after.x()) - 1f);
				int maxChunkX = terrain.getChunkX(Math.max(position.x(), after.x()) + 1f);
				int minChunkY = terrain.getChunkY(Math.min(position.y(), after.y()) - 1f);
				int maxChunkY = terrain.getChunkY(Math.max(position.y(), after.y()) + 1f);
				int minChunkZ = terrain.getChunkZ(Math.min(position.z(), after.z()) - 1f);
				int maxChunkZ = terrain.getChunkZ(Math.max(position.z(), after.z()) + 1f);
				for (int i = minChunkX; i <= maxChunkX; i++) {
					for (int j = minChunkY; j <= maxChunkY; j++) {
						for (int k = minChunkZ; k <= maxChunkZ; k++) {
							// TODO: something similar to i * i + j * j + k * k <= indexRadius * indexRadius
							TerrainChunk chunk = terrain.getChunk(i, j, k);
							chunk.getTriangles().ifPresent(triangles -> {
								triangles.forEach(triangle -> {
									CollisionPacket.checkTriangle(position, velocity, triangle, collision);
								});
							});
						}
					}
				}
			};
			world = disposables.add(new World(terrain, collisionContext));
			worldRenderer = new WorldRenderer(world);

			var dragonLoader = new ObjLoader("/res/dragon.obj", postLoadTasks::add,
					objLoader -> dragonModel = objLoader.toIndexedDrawable());
			var rocketLauncherUnloadedLoader = new ObjLoader("/res/rocket-launcher-unloaded.obj", postLoadTasks::add,
					objLoader -> rocketLauncherUnloadedModel = objLoader.toIndexedDrawable());
			var rocketLauncherLoadedLoader = new ObjLoader("/res/rocket-launcher-loaded.obj", postLoadTasks::add,
					objLoader -> rocketLauncherLoadedModel = objLoader.toIndexedDrawable());
			var rocketLauncherProjectileLoader = new ObjLoader("/res/rocket-launcher-projectile.obj", postLoadTasks::add,
					objLoader -> rocketLauncherProjectileModel = objLoader.toIndexedDrawable());

			player = new Player(new Projection(MathUtil.toRadians(60f),
					((float) window.getWidth()) / ((float) window.getHeight()), 0.01f, 1000f));
			player.mutablePosition().set(0f, 300f, 0f);

			window.pushScreen(new Loading(window::popScreen,
					dragonLoader, rocketLauncherUnloadedLoader,
					rocketLauncherLoadedLoader, rocketLauncherProjectileLoader,
					new Loader() {
				int generatorStartSize;
				@Override
				public void load() {
					worldRenderer.terrainRenderer().preload(player.position());
					generatorStartSize = generator.getQueueSize();
				}

				@Override
				public float getProgress() {
					return 1f - ((float) generator.getQueueSize()) / ((float) generatorStartSize);
				}
			}));
			loaded = true;
			return;
		}


		logger.log(Level.FINE, "Initializing");
		postLoadTasks.run();
		this.window = window;
		GLFW.glfwSetInputMode(GLFW.glfwGetCurrentContext(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
		var windowWidth = window.getWidth();
		var windowHeight = window.getHeight();

		GL11.glViewport(0, 0, windowWidth, windowHeight);

		benchmarker = new Benchmarker();
		benchmarker.put("updateData", new LineGraph(1000, 100000000));
		benchmarker.put("renderData", new LineGraph(1000, 100000000));
		benchmarker.put("fpsData", new LineGraph(1000, 100));
		benchmarker.put("debugData", new LineGraph(1000, 100));
		benchmarker.put("freeMemory", new LineGraph(1000, 5000000000f));
		benchmarker.put("totalMemory", new LineGraph(1000, 5000000000f));

		debugOverlay = disposables.add(new DebugOverlay(window, benchmarker));

		Matrix orthoProjectionMatrix = MathUtil.getOrtho(windowWidth, windowHeight, -1, 1);
		CommonProgramsSetup.setup2D(orthoProjectionMatrix);
		CommonProgramsSetup.setup3D(player.camera().getProjectionMatrix());

		updateViewMatrices();

		frameBuffer = disposables.add(new FrameBuffer());
		frameBuffer.bind(frameBuffer -> {
			GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
			Texture colorTexture = disposables.add(new Texture());
			TextureBank.COLOR.bind(() -> {
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture.getId());
				GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, windowWidth, windowHeight, 0, GL11.GL_RGB,
						GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
				GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
				GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
				GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, colorTexture.getId(), 0);
			});
			Texture depthTexture = disposables.add(new Texture());
			TextureBank.DEPTH.bind(() -> {
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture.getId());
				GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT32, windowWidth, windowHeight, 0,
						GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
				GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
				GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
				GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, depthTexture.getId(), 0);
			});
		});
		TextureBank.SKYBOX.bind(() -> {
			Texture skyboxTexture = new Texture();
			skyboxTexture.load(new SkyboxLoader("/res/darkskies", "darkskies.cfg").load());
			GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, skyboxTexture.getId());
		});
		Map.of(
				TextureBank.GRASS, "/res/grass.png",
				TextureBank.SLOPE, "/res/slope.png",
				TextureBank.ROCK, "/res/rock.png",
				TextureBank.BASE, "/res/base.png"
		).forEach((textureBank, path) -> {
			textureBank.bind(() -> {
				var texture = new Texture();
				texture.load(new TextureData(Toolbox.readImage(path).orElseThrow()));
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getId());
			});
		});

		disposables.add(GameControls.setup(window.input()));
		BasicControlActivator.bindKeyboardHold(GLFW.GLFW_KEY_T, ADD_TERRAIN);
		BasicControlActivator.bindKeyboardHold(GLFW.GLFW_KEY_Y, REMOVE_TERRAIN);

		debug = new ArrayList<>();
		debug.add(Vector3D.ZERO);

		lightPosition = player.position();

		disposables.add(window.input().keyEvent().add(event -> {
			if (event.action() == GLFW.GLFW_RELEASE) {
				if (event.key() == GLFW.GLFW_KEY_G) {
					world.entities().add(new PuzzleBall(new Location(world, player.position()), player.getVectorDirection().multiply(10f)));
				}
				if (event.key() == GLFW.GLFW_KEY_C) {
					world.entities().removeIf(x -> x instanceof PuzzleBall || x instanceof RocketLauncherProjectile);
					debug.clear();
				}
				if (event.key() == GLFW.GLFW_KEY_H) {
					int size = 20;
					for (int i = -size; i <= size; i += 5) {
						for (int j = -size; j <= size; j += 5) {
							world.entities().add(new PuzzleBall(new Location(world, Vector3D.of(i, 100, j)), Vector3D.ZERO));
						}
					}
				}
				if (event.key() == GLFW.GLFW_KEY_SPACE) {
					player.mutableVelocity().addY(2f);
				}
			}
		}));

		uiScreen = disposables.add(new UIScreen(window.input()));
		uiScreen.addButton(new Box2D(100f, 100f, 100f, 20f), Color.GREEN, x -> {
			System.out.println("Clicked");
		});
		uiScreen.addWheel(Vector2D.of(200f, 200f), 50f, 0f, Color.RED);

		disposables.add(window.onBenchmark().add(benchmark -> benchmarker.benchmark(benchmark)));
		disposables.add(window.input().mouseButtonEvent().add(event -> {
			if (event.action() == GLFW.GLFW_PRESS) {
				if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1) {
					world.entities().add(new RocketLauncherProjectile(
							new Location(world, player.position().add(player.getVectorDirection().multiply(0.99f))),
							player.getVectorDirection().multiply(2f), rocketLauncherProjectileModel));
				}
			}
		}));
		disposables.add(window.input().mouseScrollEvent().add(this::onMouseScroll));
		disposables.add(window.input().cursorPositionEvent().add(this::onMousePosition));
	}

	private final PlayerControl ADD_TERRAIN = new PlayerControl();
	private final PlayerControl REMOVE_TERRAIN = new PlayerControl();

	public Optional<Vector3D> getCrosshairLocation() {
		if (depthDistance == 1f) {
			return Optional.empty();
		}
		float realDistance = (float) (0.00997367 * Math.pow(1.0 - depthDistance + 0.0000100616, -1.00036));
		return Optional.of(player.position().add(player.getVectorDirection().multiply(realDistance)));
	}

	public void generateExplosionAtCrosshair() {
		getCrosshairLocation().ifPresent(position -> world.terrain().generateExplosion(position, 5f));
	}

	private float depthDistance = 0;

	private static float playerSpeed = 0f;
	private static final Vector3D GRAVITY_VECTOR = Vector3D.of(0f, -0.05f, 0f);

	@Override
	public void update(long deltaTime) {
		float dt = (float) (((double) deltaTime) / 3.0e7);
		if (ADD_TERRAIN.isActivated()) {
			getCrosshairLocation().ifPresent(point -> world.terrain().terraform(point, 8f, dt, 5f));
		}
		if (REMOVE_TERRAIN.isActivated()) {
			getCrosshairLocation().ifPresent(point -> world.terrain().terraform(point, 8f, dt, -5f));
		}

		float angle = (player.rotation().y() + MathUtil.PI / 2f);
		float sin = (float) Math.sin(angle);
		float cos = (float) Math.cos(angle);
		var playerHorizontalVector = Vector2D.of(playerSpeed * sin, playerSpeed * cos);
		var mutableForce = MutableVector3D.of(GRAVITY_VECTOR);
		if (GameControls.STRAFE_LEFT.isActivated()) {
			mutableForce.asXZVector().subtract(playerHorizontalVector);
		}
		if (GameControls.STRAFE_RIGHT.isActivated()) {
			mutableForce.asXZVector().add(playerHorizontalVector);
		}
		var playerForwardVector = Vector2D.of(playerSpeed * cos, -playerSpeed * sin);
		if (GameControls.MOVE_FORWARDS.isActivated()) {
			mutableForce.asXZVector().add(playerForwardVector);
		}
		if (GameControls.MOVE_BACKWARDS.isActivated()) {
			mutableForce.asXZVector().subtract(playerForwardVector);
		}
		if (GameControls.MOVE_UP.isActivated()) {
			mutableForce.addY(playerSpeed);
		}
		if (GameControls.MOVE_DOWN.isActivated()) {
			mutableForce.subtractY(playerSpeed);
		}
		collisionContext.collideWithWorld(player.mutablePosition(), player.mutableVelocity(), mutableForce, dt, CollisionResponse.SLIDE);

		// Surfing
		var targetRotation = Vector3D.of(
				(float) Math.atan(player.velocity().y() / Math.hypot(player.velocity().x(), player.velocity().z())),
				(float) (Math.PI + Math.atan2(player.velocity().x(), player.velocity().z())), 0f);
		var diff = targetRotation.subtract(player.rotation())
				.operate(x -> {
					x %= MathUtil.TAU;
					x += x < -MathUtil.PI ? MathUtil.TAU : 0f;
					x -= x > MathUtil.PI ? MathUtil.TAU : 0f;
					return x;
				});
		float diffLength = diff.length();
		if (diffLength > 0.0075f) {
			diff = diff.scaleToLength(Math.max(diffLength * 0.125f, 0.0075f));
		}
		//player.mutableRotation().add(diff);


		var totalLength = world.entities().stream().map(entity -> entity instanceof PuzzleBall ball ? ball.velocity().length() : 0).reduce(0f, Float::sum);
		world.entities().removeIf(entity -> entity instanceof PuzzleBall ball && ball.position().y() <= -300f);
		world.update(dt);

		benchmarker.getLineGraph("debugData").add(totalLength);
		float current = Runtime.getRuntime().freeMemory();
		float available = Runtime.getRuntime().totalMemory();
		benchmarker.getLineGraph("freeMemory").add(current);
		benchmarker.getLineGraph("totalMemory").add(available);
		if (GameControls.DEBUG_TOGGLE.isActivated()) {
			debugOverlay.update(
					"FPS=%d, Position=[%.02f, %.02f, %.02f], Chunk=[%d, %d, %d], NumTasks=%d, NumEntities=%d, PlayerSpeed=%f",
					window.timeSync().getFps(),
					player.position().x(),
					player.position().y(),
					player.position().z(),
					world.terrain().getChunkX(player.position().x()),
					world.terrain().getChunkY(player.position().y()),
					world.terrain().getChunkZ(player.position().z()),
					pool.getTaskCount() - pool.getCompletedTaskCount(),
					world.entities().size(),
					playerSpeed);
		}
	}

	public void onMouseScroll(MouseScrollEvent event) {
		playerSpeed += (float) (event.yOffset() / 100f);
		if (playerSpeed < 0) {
			playerSpeed = 0;
		}
		player.camera().getProjection()
				.setFov(player.camera().getProjection().getFov() + ((float) (event.yOffset() / 10000f)));
		updateProjectionMatrices();
	}

	private double lastMouseX;
	private double lastMouseY;
	private double mouseX;
	private double mouseY;
	private static final float MOUSE_SENSITIVITY = 0.001f;

	public void onMousePosition(CursorPositionEvent event) {
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		mouseX = event.x();
		mouseY = event.y();
		if (GameControls.CAMERA_ROTATE.isActivated()) {
			float deltaY = (float) (-(mouseX - lastMouseX) * MOUSE_SENSITIVITY);
			float deltaX = (float) (-(mouseY - lastMouseY) * MOUSE_SENSITIVITY);
			player.mutableRotation().asXYVector().add(deltaX, deltaY)
					.clampX(-MathUtil.PI / 2f, MathUtil.PI / 2f).modY(MathUtil.TAU);
		}
	}

	public void updateViewMatrices() {
		updateViewMatrix(CommonPrograms3D.COLOR);
		updateViewMatrix(CommonPrograms3D.TEXTURE);
		updateCubeMapMatrix(CommonPrograms3D.CUBEMAP);
		updateViewMatrix(CommonPrograms3D.PARTICLE);
		updateViewMatrix(CommonPrograms3D.LIGHT);
		updateViewMatrix(CommonPrograms3D.TERRAIN);
	}

	public void updateProjectionMatrices() {
		updateProjectionMatrix(CommonPrograms3D.COLOR);
		updateProjectionMatrix(CommonPrograms3D.TEXTURE);
		updateProjectionMatrix(CommonPrograms3D.CUBEMAP);
		updateProjectionMatrix(CommonPrograms3D.PARTICLE);
		updateProjectionMatrix(CommonPrograms3D.LIGHT);
		updateProjectionMatrix(CommonPrograms3D.TERRAIN);
	}

	public void updateViewMatrix(ShaderProgramHolder holder) {
		holder.getShaderProgram().use(program -> {
			program.loadMatrix(MatrixType.VIEW_MATRIX, player.camera().getTransformationMatrix());
		});
	}

	public void updateCubeMapMatrix(ShaderProgramHolder holder) {
		holder.getShaderProgram().use(program -> {
			program.loadMatrix(MatrixType.VIEW_MATRIX, player.camera().getInvertedRotationMatrix());
		});
	}

	public void updateProjectionMatrix(ShaderProgramHolder holder) {
		holder.getShaderProgram().use(program -> {
			program.loadMatrix(MatrixType.PROJECTION_MATRIX, player.camera().getProjectionMatrix());
		});
	}

	@Override
	public void render() {
		updateViewMatrices();
		frameBuffer.bind(frameBuffer -> {
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			GL11.glDepthMask(false);
			// render skybox
			CommonPrograms3D.CUBEMAP.getShaderProgram().use(program -> {
				CommonDrawables.SKYBOX.draw();
			});
			GL11.glDepthMask(true);
			for (Vector3D x : debug) {
				PuzzleBall.render(x, Vector3D.of(0.2f, 0.2f, 0.2f));
			}
			//debug.clear();
			worldRenderer.render(player.position());
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			CommonPrograms3D.LIGHT.getShaderProgram().use(program -> {
				var position = Vector3D.of(96f, 40f, 0f);
				try (var translationMatrix = MatrixPool.ofTranslation(position);
					 var scalarMatrix = MatrixPool.ofScalar(8f, 8f, 8f)) {
					var sunlightDirection = lightPosition.subtract(position).normalize();
					program.loadMatrix(MatrixType.MODEL_MATRIX, translationMatrix.multiply(scalarMatrix));
					program.loadVector("sunlightDirection", sunlightDirection);
					program.loadVector("viewPos", player.position());
				}
				dragonModel.draw();
			});
			CommonPrograms3D.LIGHT.getShaderProgram().use(program -> {
				var sunlightDirection = lightPosition.normalize();
				program.loadMatrix(MatrixType.MODEL_MATRIX, Matrix.IDENTITY_4);
				program.loadVector("sunlightDirection", sunlightDirection);
				program.loadVector("viewPos", player.position());
				rocketLauncherProjectileModel.draw();
			});
			CommonPrograms3D.LIGHT.getShaderProgram().use(program -> {
				try (var translationMatrix = MatrixPool.ofTranslation(Vector3D.of(3.5f, -4f, 1f));
					 var rotationMatrix = MatrixPool.ofRotationY(MathUtil.PI / 2f)) {
					var sunlightDirection = Vector3D.of(-3.5f, 4f, -1f).normalize();
					program.loadMatrix(MatrixType.MODEL_MATRIX, (rotationMatrix.multiply(translationMatrix)));
					program.loadVector("sunlightDirection", sunlightDirection);
					program.loadVector("viewPos", player.position());
					program.loadMatrix(MatrixType.VIEW_MATRIX, Matrix.IDENTITY_4);
				}
				rocketLauncherLoadedModel.draw();
				//rocketLauncherUnloadedModel.draw();
			});
			GL11.glDisable(GL11.GL_DEPTH_TEST);
			FloatBuffer depthPixelBuffer = BufferUtils.createFloatBuffer(1);
			GL11.glReadPixels(window.getWidth() / 2, window.getHeight() / 2, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthPixelBuffer);
			depthDistance = depthPixelBuffer.get(); // far plane
		});
		CommonPrograms3D.POST_PROCESSING.getShaderProgram().use(program -> {
			CommonDrawables.TEXTURED_QUAD.draw();
		});
		CommonPrograms2D.COLOR.getShaderProgram().use(program -> {
			// Render crosshair
			try (var translationMatrix = MatrixPool.ofTranslation(window.getWidth() / 2f, window.getHeight() / 2f, 0f);
				 var scalarMatrixA = MatrixPool.ofScalar(5f, 1f, 1f);
				 var scalarMatrixB = MatrixPool.ofScalar(1f, 5f, 1f);
				 var matrixA = MatrixPool.ofMultiplied(translationMatrix, scalarMatrixA);
				 var matrixB = MatrixPool.ofMultiplied(translationMatrix, scalarMatrixB)) {
				program.loadMatrix(MatrixType.TRANSFORMATION_MATRIX, matrixA);
				CommonDrawables.COLORED_QUAD.draw();
				program.loadMatrix(MatrixType.TRANSFORMATION_MATRIX, matrixB);
				CommonDrawables.COLORED_QUAD.draw();
			}
		});
		//uiScreen.render();
		if (GameControls.DEBUG_TOGGLE.isActivated()) {
			debugOverlay.render();
		}
	}

	@Override
	public void dispose() {
		disposables.dispose();
	}
}
