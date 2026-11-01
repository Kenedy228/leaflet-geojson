import { Api, ApiError, coordinate } from "./api";
import type {
  DistanceResult,
  Draft,
  GeoItem,
  LayerName,
  Session,
} from "./types";
interface LayerState {
  visible: boolean;
  loading: boolean;
  error: string;
  data: GeoItem[];
}
const layer = (): LayerState => ({
  visible: false,
  loading: false,
  error: "",
  data: [],
});
const readonly = (): Session => ({
  editing_enabled: false,
  can_edit: false,
  csrf_header: "X-CSRF-TOKEN",
  csrf_token: "",
});
const message = (error: unknown) =>
  error instanceof Error ? error.message : "Не удалось выполнить запрос.";
export class AppModel {
  layers = $state<Record<LayerName, LayerState>>({
    points: layer(),
    roads: layer(),
    polygons: layer(),
  });
  access = $state<Session>(readonly());
  accessError = $state("");
  accessPending = $state(false);
  drafts = $state<Draft[]>([]);
  addMode = $state(false);
  distanceMode = $state(false);
  selected = $state<GeoItem[]>([]);
  deleteTarget = $state<GeoItem | null>(null);
  distance = $state<DistanceResult | null>(null);
  distancePending = $state(false);
  distanceError = $state("");
  savePending = $state(false);
  savingIds = $state<string[]>([]);
  saveStatus = $state("");
  deletePending = $state(false);
  deleteStatus = $state("");
  private confirmed = $state<GeoItem[]>([]);
  private deleted = $state<string[]>([]);
  private sequence = 0;
  private alive = true;
  private lifecycle = new AbortController();
  private requests: Partial<Record<LayerName, AbortController>> = {};
  private versions: Record<LayerName, number> = {
    points: 0,
    roads: 0,
    polygons: 0,
  };
  private distanceRequest: AbortController | undefined;
  private distanceVersion = 0;
  private sessionVersion = 0;
  constructor(private api = new Api()) {}
  get points() {
    if (!this.layers.points.visible) return [];
    const items: Record<string, GeoItem> = Object.fromEntries(
      this.layers.points.data.map((p) => [p.id, p]),
    );
    this.confirmed.forEach((p) => {
      items[p.id] = p;
    });
    this.deleted.forEach((id) => {
      delete items[id];
    });
    return Object.values(items);
  }
  async refreshSession() {
    const version = ++this.sessionVersion;
    try {
      const access = await this.api.session(this.lifecycle.signal);
      if (!this.alive || version !== this.sessionVersion)
        throw new Error("Состояние сеанса изменилось. Повторите действие.");
      if (this.alive) {
        this.access = access;
        this.accessError = "";
        if (!access.can_edit) this.addMode = false;
      }
      return access;
    } catch (error) {
      if (this.alive && version === this.sessionVersion) {
        this.access = readonly();
        this.addMode = false;
        this.accessError = message(error);
      }
      throw error;
    }
  }
  async toggleLayer(name: LayerName, visible: boolean) {
    const state = this.layers[name];
    this.requests[name]?.abort();
    const version = ++this.versions[name];
    state.visible = visible;
    state.data = [];
    state.error = "";
    state.loading = false;
    if (name === "points" && !visible) {
      this.setDistanceMode(false);
      this.deleteTarget = null;
    }
    if (!visible) return;
    // Only writes confirmed after this GET began may overlay its result.
    const priorConfirmed = this.confirmed.map((point) => point.id);
    const priorDeleted = [...this.deleted];
    const request = new AbortController();
    this.requests[name] = request;
    state.loading = true;
    try {
      const data = await this.api.layer(name, request.signal);
      if (this.alive && this.versions[name] === version && state.visible) {
        state.data = data;
        if (name === "points") {
          this.confirmed = this.confirmed.filter(
            (point) => !priorConfirmed.includes(point.id),
          );
          this.deleted = this.deleted.filter(
            (id) => !priorDeleted.includes(id),
          );
        }
      }
    } catch (error) {
      if (
        this.alive &&
        this.versions[name] === version &&
        !request.signal.aborted
      )
        state.error = message(error);
    } finally {
      if (this.alive && this.versions[name] === version) state.loading = false;
    }
  }
  setAddMode(value: boolean) {
    this.addMode = value && this.access.can_edit;
    this.deleteTarget = null;
    if (this.addMode) this.setDistanceMode(false);
  }
  setDistanceMode(value: boolean) {
    this.distanceMode = value && this.layers.points.visible;
    if (this.distanceMode) {
      this.addMode = false;
      this.deleteTarget = null;
    }
    this.clearDistance();
  }
  addDraft(lat: number, lng: number) {
    if (!this.addMode || !this.access.can_edit) return;
    try {
      coordinate([lng, lat]);
    } catch {
      this.saveStatus = "Укажите допустимые координаты WGS84.";
      return;
    }
    this.drafts.push({
      id: `draft:${++this.sequence}`,
      name: "Новая точка",
      lat,
      lng,
    });
  }
  renameDraft(id: string, name: string) {
    if (this.savingIds.includes(id)) return;
    const draft = this.drafts.find((d) => d.id === id);
    if (draft) draft.name = name;
  }
  dropDraft(id: string) {
    if (!this.savingIds.includes(id))
      this.drafts = this.drafts.filter((d) => d.id !== id);
  }
  clearDistance() {
    this.distanceRequest?.abort();
    this.distanceVersion++;
    this.selected = [];
    this.distance = null;
    this.distancePending = false;
    this.distanceError = "";
  }
  clickPoint(point: GeoItem) {
    if (this.addMode) return;
    if (!this.distanceMode) {
      this.deleteTarget = point.source === "user" ? point : null;
      return;
    }
    this.distanceRequest?.abort();
    this.distanceVersion++;
    this.distance = null;
    this.distancePending = false;
    this.distanceError = "";
    this.selected = this.selected.some((p) => p.id === point.id)
      ? this.selected.filter((p) => p.id !== point.id)
      : [...this.selected, structuredClone($state.snapshot(point))];
  }
  async calculate() {
    if (!this.distanceMode || this.selected.length < 2) {
      this.distanceError = "Выберите хотя бы 2 точки.";
      return;
    }
    this.distanceRequest?.abort();
    const request = new AbortController();
    this.distanceRequest = request;
    const version = ++this.distanceVersion;
    this.distance = null;
    this.distanceError = "";
    this.distancePending = true;
    const points = this.selected.map((p) =>
      coordinate(
        p.geojson.type === "Point" ? p.geojson.coordinates : undefined,
      ),
    );
    try {
      const result = await this.api.distance(points, request.signal);
      if (this.alive && version === this.distanceVersion)
        this.distance = result;
    } catch (error) {
      if (
        this.alive &&
        version === this.distanceVersion &&
        !request.signal.aborted
      )
        this.distanceError = message(error);
    } finally {
      if (this.alive && version === this.distanceVersion)
        this.distancePending = false;
    }
  }
  async save() {
    if (
      this.savePending ||
      this.accessPending ||
      !this.access.can_edit ||
      !this.drafts.length
    )
      return;
    const snapshot = this.drafts.map((d) => ({ ...d }));
    this.savePending = true;
    this.savingIds = snapshot.map((d) => d.id);
    this.saveStatus = "Сохранение точек…";
    try {
      const access = await this.refreshSession();
      if (!access.can_edit || !this.access.can_edit || this.accessPending)
        throw new Error("Редактирование недоступно. Войдите как редактор.");
      const saved = await this.api.save(
        snapshot,
        access,
        this.lifecycle.signal,
      );
      if (!this.alive) return;
      this.confirmed = [...this.confirmed, ...saved];
      this.drafts = this.drafts.filter((d) => !this.savingIds.includes(d.id));
      this.saveStatus = `Сохранено точек: ${saved.length}.`;
    } catch (error) {
      if (this.alive) {
        this.saveStatus = `${message(error)} Черновики сохранены; проверьте точки перед повторной отправкой.`;
        // Logout owns the session generation until its preflight and POST finish.
        if (!this.accessPending) {
          try {
            await this.refreshSession();
          } catch {
            /* readonly fallback */
          }
        }
      }
    } finally {
      if (this.alive) {
        this.savePending = false;
        this.savingIds = [];
      }
    }
  }
  async deleteSelected() {
    if (
      !this.deleteTarget ||
      !this.access.can_edit ||
      this.accessPending ||
      this.deletePending
    )
      return;
    const id = this.deleteTarget.id;
    this.deletePending = true;
    this.deleteStatus = "Удаление точки…";
    try {
      const access = await this.refreshSession();
      if (!access.can_edit || !this.access.can_edit || this.accessPending)
        throw new Error("Редактирование недоступно.");
      await this.api.remove(id, access, this.lifecycle.signal);
      if (!this.alive) return;
      this.deleted = [...this.deleted, id];
      this.clearDistance();
      if (this.deleteTarget?.id === id) this.deleteTarget = null;
      this.deleteStatus = "Точка удалена.";
    } catch (error) {
      if (this.alive) {
        if (error instanceof ApiError && error.status === 404) {
          this.deleted = [...this.deleted, id];
          this.clearDistance();
          if (this.deleteTarget?.id === id) this.deleteTarget = null;
        }
        this.deleteStatus = message(error);
        // Logout owns the session generation until its preflight and POST finish.
        if (!this.accessPending) {
          try {
            await this.refreshSession();
          } catch {
            /* readonly fallback */
          }
        }
      }
    } finally {
      if (this.alive) this.deletePending = false;
    }
  }
  async logout() {
    if (this.accessPending) return;
    this.accessPending = true;
    this.sessionVersion++;
    this.access = readonly();
    this.addMode = false;
    try {
      const access = await this.refreshSession();
      await this.api.logout(access, this.lifecycle.signal);
      if (this.alive) {
        this.sessionVersion++;
        this.access = readonly();
        this.addMode = false;
        await this.refreshSession();
      }
    } catch (error) {
      if (this.alive) this.accessError = message(error);
    } finally {
      if (this.alive) this.accessPending = false;
    }
  }
  destroy() {
    this.alive = false;
    this.sessionVersion++;
    this.lifecycle.abort();
    Object.values(this.requests).forEach((request) => request.abort());
    this.distanceRequest?.abort();
    this.distanceVersion++;
    Object.keys(this.versions).forEach(
      (name) => this.versions[name as LayerName]++,
    );
  }
}
