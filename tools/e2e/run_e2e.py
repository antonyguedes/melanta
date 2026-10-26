#!/usr/bin/env python3
"""Testes de ponta a ponta do agente num emulador ou aparelho, via adb (build de debug).

Para cada tarefa: inicia pelo DebugTaskActivity, acompanha o log local da execução
(files/agent-logs), responde às confirmações e confere o efeito real no aparelho.

uso: python3 tools/e2e/run_e2e.py [--serial emulator-5554] [--only alarme,timer] [--timeout 300]
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PKG = "com.melanta"
A11Y = "com.melanta/com.melanta.core.automation.service.AgentAccessibilityService"
RECEIVER = "com.melanta/com.melanta.debug.AutomationDebugReceiver"


class Device:
    def __init__(self, serial):
        self.base = [ADB] + (["-s", serial] if serial else [])

    def sh(self, *cmd, timeout=60):
        out = subprocess.run(self.base + ["shell", *cmd], capture_output=True, text=True, timeout=timeout)
        return out.stdout

    def run_as(self, *cmd):
        return self.sh("run-as", PKG, *cmd)

    def logs(self):
        return sorted(self.run_as("ls", "files/agent-logs").split())

    def log_events(self, name):
        events = []
        for line in self.run_as("cat", f"files/agent-logs/{name}").splitlines():
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                pass
        return events

    def foreground(self):
        out = self.sh("dumpsys", "activity", "activities")
        for line in out.splitlines():
            if "ResumedActivity" in line and "/" in line:
                return line.split("/")[0].split()[-1]
        return ""

    def confirmation_pending(self):
        return "channel=agent_confirm" in self.sh("dumpsys", "notification", "--noredact")

    def answer_permission_dialog(self):
        """Faz o papel do usuário num pedido de permissão (o agente não enxerga essa janela)."""
        if "permissioncontroller" not in self.sh("dumpsys", "activity", "activities"):
            return False
        self.sh("uiautomator", "dump", "/sdcard/ui.xml")
        xml = self.sh("cat", "/sdcard/ui.xml")
        m = re.search(r'resource-id="[^"]*permission_allow[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if not m:
            return False
        x1, y1, x2, y2 = map(int, m.groups())
        self.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(1)
        return True


# Cada tarefa: texto, se aprova confirmações e a checagem do efeito real.
def check_log(events, needle):
    return any(e.get("type") == "action" and needle in e.get("action", "") and e.get("result") == "Ok" for e in events)


TASKS = {
    "alarme": ("Põe um alarme para 7h", True, lambda d, ev: check_log(ev, "Alarm(hour=7, minute=0")),
    "timer": ("Coloca um timer de 5 minutos", True, lambda d, ev: check_log(ev, "Timer(seconds=300")),
    "busca": ("Pesquisa a previsão do tempo para amanhã", True, lambda d, ev: check_log(ev, "WebSearch(")),
    "youtube": ("Abre o YouTube", True, lambda d, ev: "youtube" in d.foreground()),
    "discar": ("Liga para 11 98765-4321", True, lambda d, ev: check_log(ev, "Dial(target=")),
    "contato": ("Crie um contato de telefone com Nome Jose e o numero +55 24 987778990", True,
                lambda d, ev: "Jose" in d.sh("content", "query", "--uri", "content://com.android.contacts/data/phones",
                                             "--projection", "display_name")),
    "wifi": ("Desliga o wi-fi", True, lambda d, ev: "disabled" in d.sh("cmd", "wifi", "status").lower()),
    "sms": ("Manda SMS para 11 99999-8888 dizendo oi", True,
            lambda d, ev: any(e.get("type") == "confirmation" and e.get("approved") for e in ev)),
    "rota": ("Me leva até a Avenida Paulista", True, lambda d, ev: check_log(ev, "Navigate(")),
    "evento": ("Marca dentista amanhã às 15h na agenda", True, lambda d, ev: check_log(ev, "Event(")),
}


MAX_ANSWERS = 3


def prepare(dev):
    dev.sh("settings", "put", "secure", "enabled_accessibility_services", A11Y)
    dev.sh("settings", "put", "secure", "accessibility_enabled", "1")
    dev.sh("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    dev.sh("svc", "wifi", "enable")
    # YouTube volta a pedir permissão de notificação na abertura: exercita a passagem de vez para o usuário.
    dev.sh("pm", "revoke", "com.google.android.youtube", "android.permission.POST_NOTIFICATIONS")
    dev.sh("pm", "clear-permission-flags", "com.google.android.youtube", "android.permission.POST_NOTIFICATIONS",
           "user-set", "user-fixed")
    dev.sh("am", "force-stop", "com.google.android.youtube")
    # Contatos de rodadas anteriores fariam a checagem de "contato" passar sem o agente salvar nada.
    dev.sh("content", "delete", "--uri", "content://com.android.contacts/raw_contacts?caller_is_syncadapter=true",
           "--where", shlex.quote("display_name IN ('Jose','Maria')"))
    time.sleep(2)


def run_task(dev, key, timeout):
    text, approve, check = TASKS[key]
    # Uma tarefa ainda ativa faz o agente recusar a próxima: espera o serviço encerrar.
    for _ in range(60):
        if "AgentForegroundService}" not in dev.sh("dumpsys", "activity", "services", PKG):
            break
        time.sleep(2)
    dev.sh("input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)
    before = set(dev.logs())
    t0 = time.time()
    # adb shell junta os argumentos e o shell do aparelho separa de novo: o texto precisa de aspas.
    dev.sh("am", "start", "-n", f"{PKG}/{PKG}.debug.DebugTaskActivity", "--es", "task", shlex.quote(text))
    name, events, answered = None, [], 0
    while time.time() - t0 < timeout:
        time.sleep(2)
        if name is None:
            new = sorted(set(dev.logs()) - before)
            name = new[-1] if new else None
            continue
        if answered < MAX_ANSWERS and dev.confirmation_pending():
            dev.answer_permission_dialog()
            dev.sh("am", "broadcast", "-n", RECEIVER, "-a", "com.melanta.debug." + ("CONFIRM" if approve else "DENY"))
            answered += 1
            time.sleep(3)
        events = dev.log_events(name)
        if any(e.get("type") == "end" for e in events):
            break
    elapsed = time.time() - t0
    end = next((e["result"] for e in events if e.get("type") == "end"), "sem fim (timeout)")
    steps = [f'{e.get("tool")} {e.get("args")}' for e in events if e.get("type") == "decision"]
    time.sleep(1)
    # O efeito precisa existir e o agente precisa ter terminado com sucesso (intent Ok seguido de loop não vale).
    ok = bool(events) and end.startswith("Finished") and check(dev, events)
    return dict(task=key, ok=ok, end=end, steps=steps, seconds=round(elapsed, 1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--only", help="tarefas separadas por vírgula: " + ",".join(TASKS))
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--json", help="grava o resultado em JSON")
    args = parser.parse_args()
    dev = Device(args.serial)
    keys = args.only.split(",") if args.only else list(TASKS)
    prepare(dev)
    results = []
    for key in keys:
        r = run_task(dev, key, args.timeout)
        results.append(r)
        print(f"{'✓' if r['ok'] else '✗'} {key:8} {r['seconds']:6.1f}s  {len(r['steps'])} passos  {r['end'][:70]}", flush=True)
        for s in r["steps"]:
            print(f"      {s[:110]}", flush=True)
        if key == "wifi":
            dev.sh("svc", "wifi", "enable")
    passed = sum(r["ok"] for r in results)
    print(f"\nRESUMO: {passed}/{len(results)} tarefas concluídas · "
          f"{sum(r['seconds'] for r in results) / max(1, len(results)):.0f} s por tarefa em média")
    if args.json:
        with open(args.json, "w") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
