"""CI-only interaction with the same password setup and doas used by people."""
PASSWORD = b'Flyby-test-setup-42!'
class Login:
    def __init__(self, send):
        self.send = send
        self.tail = b''
        self.setup_new = False
        self.setup_repeat = False
        self.elevating = False
        self.authenticated = False
        self.ready = False
        self.user_ok = False

    def feed(self, data):
        self.tail = (self.tail + data)[-8192:]
        self.user_ok |= b'\r\nFLYBY_USER_OK\r\n' in self.tail
        lower = self.tail.lower()
        if not self.setup_new and b'new password:' in lower:
            self.send(PASSWORD+b'\n')
            self.setup_new = True
            self.tail = b''
        elif not self.setup_repeat and b'retype password:' in lower:
            self.send(PASSWORD+b'\n')
            self.setup_repeat = True
            self.tail = b''
        elif not self.elevating and b'FLYBY_ALPINE_READY' in self.tail:
            self.send(b"""[ "$(id -u)" = 1000 ] && [ "$HOME" = /home/flybyuser ] && ! test -w /etc/passwd && ! doas -n true && printf '\\nFLYBY_USER_OK\\n'
doas /bin/sh -l
""")
            self.elevating = True
            self.tail = b''
        elif self.elevating and not self.authenticated and b'password:' in lower:
            self.send(PASSWORD+b'\n')
            self.authenticated = True
            self.tail = b''
        elif self.authenticated and b'root@flyby:' in self.tail:
            if not self.user_ok: raise RuntimeError('Ordinary account permission checks failed')
            self.ready = True
        return self.ready
